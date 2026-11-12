"""スマホ探し(PC側): 同じネットワーク内のスマホを鳴らす。

通信の流れ(UDP 47321、すべて共有鍵のHMAC-SHA256で署名):
  PC   -> 'H' + cn(8)                 探索
  端末 -> 'C' + cn + sn(16) + HMAC    チャレンジ(鍵を知っている証明)
  PC   -> 'R'|'S' + sn + HMAC         鳴らす / 止める(sn は1回限り)
  端末 -> 'A' + cmd + sn + HMAC       受理通知
"""
import base64
import hashlib
import hmac
import ipaddress
import os
import select
import socket
import sys
import threading
import time

try:
    import tkinter as tk
except ImportError:  # 画面なしの環境(通信部分のテスト用)
    tk = None

APP_NAME = "FindPhone"
VERSION = "1.1.0"
PORT = 47321
APP_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), APP_NAME)
KEY_FILE = os.path.join(APP_DIR, "key.bin")
LAST_IP_FILE = os.path.join(APP_DIR, "last_ip.txt")

LINK_CHECK_INTERVAL = 3.0   # 接続確認の間隔(秒)
SWEEP_EVERY_FAILS = 5       # 接続確認がこの回数続けて失敗したら、全端末へ探索を送る


# ---- 表示テキスト(日本語 / 英語) ----

def _is_japanese() -> bool:
    try:
        import ctypes
        return ctypes.windll.kernel32.GetUserDefaultUILanguage() & 0x3FF == 0x11
    except Exception:
        return os.environ.get("LANG", "").lower().startswith("ja")


TEXTS = {
    "title": ("スマホ探し", "Find Phone"),
    "ring": ("鳴らす", "Ring"),
    "stop": ("止める", "Stop"),
    "pair": ("ペアリング", "Pair"),
    "show_log": ("ログを表示", "Show log"),
    "hide_log": ("ログを隠す", "Hide log"),
    "link_checking": ("確認中…", "Checking…"),
    "link_ok": ("接続中  {ip}", "Connected  {ip}"),
    "link_ng": ("未接続", "Not connected"),
    "link_unpaired": ("未ペアリング", "Not paired"),
    "msg_ready": ("ボタンを押すとスマホが鳴ります", "Press the button to ring your phone"),
    "msg_unpaired": ("最初にペアリングしてください", "Pair with your phone first"),
    "msg_searching": ("探しています…", "Searching…"),
    "msg_ringing": ("鳴らしています", "Ringing"),
    "msg_stopped": ("止めました", "Stopped"),
    "msg_not_found": ("見つかりません。スマホの「ヘルプ」で診断できます",
                      "Not found. Open Help on the phone to diagnose"),
    "msg_paired": ("ペアリングしました", "Paired"),
    "pair_prompt": ("スマホに表示されたペアリングコードを入力", "Enter the pairing code shown on the phone"),
    "pair_bad": ("コードが正しくありません", "The code is not valid"),
    "pair_save_failed": ("鍵を保存できません: {e}", "Could not save the key: {e}"),
    "ok": ("OK", "OK"),
    "cancel": ("キャンセル", "Cancel"),
    "log_start": ("起動 v{v}", "Started v{v}"),
    "log_no_lan": ("LANのIPアドレスが見つかりません(ネットワーク未接続?)", "No LAN address found (network disconnected?)"),
    "log_from": ("送信元: {ips}", "Sending from: {ips}"),
    "log_wrong_key": ("{ip} が応答しましたが鍵が違います。再ペアリングしてください",
                      "{ip} answered with a different key. Pair again"),
    "log_found": ("スマホ発見: {ip}", "Phone found: {ip}"),
    "log_sent": ("命令を送信", "Command sent"),
    "log_ack": ("スマホが命令を受理", "Phone accepted the command"),
    "log_timeout": ("{sec:.0f}秒以内に応答なし", "No answer within {sec:.0f} s"),
    "log_link_ok": ("接続確認: 接続中 {ip}", "Link check: connected {ip}"),
    "log_link_ng": ("接続確認: 応答なし", "Link check: no answer"),
}
_LANG = 0 if _is_japanese() else 1


def tr(key: str, **values) -> str:
    return TEXTS[key][_LANG].format(**values)


# ---- 鍵の保管(Windows DPAPI: ログイン中のユーザーだけが復号できる) ----

def _dpapi(data: bytes, protect: bool) -> bytes:
    import ctypes
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    src = Blob(len(data), ctypes.cast(ctypes.create_string_buffer(data, len(data)), ctypes.POINTER(ctypes.c_char)))
    out = Blob()
    crypt = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not crypt(ctypes.byref(src), None, None, None, None, 0, ctypes.byref(out)):
        raise OSError("DPAPI failed")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


def save_key(key: bytes) -> None:
    os.makedirs(APP_DIR, exist_ok=True)
    with open(KEY_FILE, "wb") as f:
        f.write(_dpapi(key, True))


def load_key():
    try:
        with open(KEY_FILE, "rb") as f:
            return _dpapi(f.read(), False)
    except Exception:
        return None


def parse_code(text: str) -> bytes:
    """ペアリングコード(Base32)を鍵に戻す。区切りの空白や小文字は許容する。"""
    s = "".join(ch for ch in text.upper() if ch.isalnum())
    key = base64.b32decode(s + "=" * (-len(s) % 8))
    if len(key) not in (16, 20):  # 16=現行 / 20=旧版アプリ
        raise ValueError("length")
    return key


def load_last_ip():
    """前回見つけたスマホのIP(秘密ではない)。次回の探索を速くするために覚えておく。"""
    try:
        with open(LAST_IP_FILE, encoding="ascii") as f:
            return str(ipaddress.IPv4Address(f.read().strip()))
    except Exception:
        return None


def save_last_ip(ip: str) -> None:
    try:
        os.makedirs(APP_DIR, exist_ok=True)
        with open(LAST_IP_FILE, "w", encoding="ascii") as f:
            f.write(ip)
    except OSError:
        pass


# ---- 通信 ----

def mac(key: bytes, *parts: bytes) -> bytes:
    return hmac.new(key, b"".join(parts), hashlib.sha256).digest()


def lan_ips():
    """このPCが持つLAN内のIPv4アドレス。"""
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except (OSError, UnicodeError):
        pass
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("10.255.255.255", 1))  # 実際には送信しない。既定の経路のIPを知るため
        ips.add(probe.getsockname()[0])
    except OSError:
        pass
    finally:
        probe.close()
    return sorted(ip for ip in ips
                  if ipaddress.ip_address(ip).is_private and not ip.startswith(("127.", "169.254.")))


def _targets(own_ip: str, last_ip, sweep: bool):
    """探索の宛先。普段は「前回のIP+ブロードキャスト」だけにして通信量を抑える。"""
    prefix = own_ip.rsplit(".", 1)[0]
    targets = [last_ip] if last_ip else []
    targets += [prefix + ".255", "255.255.255.255"]
    if sweep:  # スリープ中の端末はブロードキャストを捨てることがあるため、同じ /24 の全端末へ直接送る
        targets += [f"{prefix}.{n}" for n in range(1, 255) if f"{prefix}.{n}" not in (own_ip, last_ip)]
    return targets


def find_phone(key: bytes, cmd=None, timeout: float = 5.0, last_ip=None, sweep_after=1.0,
               log=lambda msg: None, ips=None):
    """スマホを探し、見つけたらそのIPを返す(見つからなければ None)。

    cmd が b'R'(鳴らす) / b'S'(止める) なら命令を送り、受理通知まで確認する。
    cmd が None なら、正しい鍵で応答する端末がいるかだけを確かめる(接続確認用)。
    sweep_after 秒たっても見つからなければ全端末へ探索を送る。None なら送らない。
    """
    cn = os.urandom(8)
    socks = []
    for ip in (ips if ips is not None else lan_ips()):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            s.bind((ip, 0))
            if hasattr(socket, "SIO_UDP_CONNRESET"):  # Windows: ICMP到達不能で受信が例外になるのを防ぐ
                s.ioctl(socket.SIO_UDP_CONNRESET, False)
            socks.append((ip, s))
        except OSError:
            s.close()
    if not socks:
        log(tr("log_no_lan"))
        return None
    if cmd is not None:
        log(tr("log_from", ips=", ".join(ip for ip, _ in socks)))

    try:
        start = time.monotonic()
        next_hello = start
        waiting_ack = {}  # socket -> (sn, 送信時刻)
        while True:
            now = time.monotonic()
            if now >= start + timeout:
                break
            awaiting = any(now - sent < 1.0 for _, sent in waiting_ack.values())
            if now >= next_hello and not awaiting:  # 1秒ごとに再送(UDP取りこぼし対策)
                next_hello = now + 1.0
                sweep = sweep_after is not None and now - start >= sweep_after
                for ip, s in socks:
                    for dst in _targets(ip, last_ip, sweep):
                        try:
                            s.sendto(b"H" + cn, (dst, PORT))
                        except OSError:
                            pass
            ready, _, _ = select.select([s for _, s in socks], [], [], 0.2)
            for s in ready:
                try:
                    data, addr = s.recvfrom(128)
                except OSError:
                    continue
                if len(data) == 57 and data[:1] == b"C" and data[1:9] == cn:
                    sn = data[9:25]
                    if not hmac.compare_digest(data[25:], mac(key, b"C", cn, sn)):
                        log(tr("log_wrong_key", ip=addr[0]))
                        continue  # 鍵を知らない相手は無視
                    if cmd is None:
                        return addr[0]
                    if s in waiting_ack and time.monotonic() - waiting_ack[s][1] < 1.0:
                        continue  # 命令は送信済み。受理通知を待つ
                    log(tr("log_found", ip=addr[0]))
                    s.sendto(cmd + sn + mac(key, cmd, sn), addr)
                    waiting_ack[s] = (sn, time.monotonic())
                    log(tr("log_sent"))
                elif len(data) == 50 and data[:1] == b"A" and data[1:2] == cmd and s in waiting_ack:
                    sn = waiting_ack[s][0]
                    if data[2:18] == sn and hmac.compare_digest(data[18:], mac(key, b"A", cmd, sn)):
                        log(tr("log_ack"))
                        return addr[0]
        if cmd is not None:
            log(tr("log_timeout", sec=timeout))
        return None
    finally:
        for _, s in socks:
            s.close()


# ---- Windows向けの下ごしらえ ----

def _resource(name: str) -> str:
    """同梱ファイルの場所(exe化した時は展開先、スクリプト実行時は同じフォルダ)。"""
    return os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), name)


def _already_running() -> bool:
    """二重起動なら、既に開いているウィンドウを前面に出して True を返す。"""
    try:
        import ctypes
        ctypes.windll.kernel32.CreateMutexW(None, False, "Local\\FindPhonePC")
        if ctypes.windll.kernel32.GetLastError() != 183:  # ERROR_ALREADY_EXISTS
            return False
        hwnd = ctypes.windll.user32.FindWindowW(None, tr("title"))
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 9)  # SW_RESTORE
            ctypes.windll.user32.SetForegroundWindow(hwnd)
        return True
    except Exception:
        return False


def _enable_hidpi() -> None:
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # 高解像度の画面でも文字をくっきり表示
    except Exception:
        pass


# ---- 画面 ----

BG = "#0E1013"
CARD = "#1A1D22"
CARD_HIGH = "#272B33"
TEXT = "#ECEFF4"
SUB = "#9AA3AF"
ACCENT = "#3DDC84"
ACCENT_HOVER = "#5BE89A"
ACCENT_BUSY = "#1F6F43"
ON_ACCENT = "#06210F"
BAD = "#FF5252"
FONT = "Yu Gothic UI" if _LANG == 0 else "Segoe UI"


class App:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title(tr("title"))
        self.root.configure(bg=BG)
        self.root.resizable(False, False)
        try:
            self.root.iconbitmap(_resource("icon.ico"))
        except Exception:
            pass
        self.scale = self.root.winfo_fpixels("1i") / 96.0

        self.key = load_key()
        self.last_ip = load_last_ip()
        self.busy = False
        self.log_visible = False
        self._link_state = None

        self._build()
        self.log(tr("log_start", v=VERSION))
        self.show(tr("msg_unpaired") if self.key is None else tr("msg_ready"))
        self._set_link(None)
        threading.Thread(target=self._watch_link, daemon=True).start()

    def px(self, value: float) -> int:
        return round(value * self.scale)

    def _build(self):
        root = self.root
        tk.Label(root, text=tr("title"), fg=TEXT, bg=BG, font=(FONT, 15, "bold")).pack(pady=(self.px(18), 0))
        self.link = tk.Label(root, text="", fg=SUB, bg=BG, font=(FONT, 10))
        self.link.pack(pady=(self.px(4), 0))

        size = self.px(168)
        self.ring = tk.Canvas(root, width=size, height=size, bg=BG, highlightthickness=0, cursor="hand2")
        self.ring.pack(pady=self.px(18))
        m = self.px(4)
        self.ring_circle = self.ring.create_oval(m, m, size - m, size - m, fill=ACCENT, outline="")
        self.ring_label = self.ring.create_text(size // 2, size // 2, text=tr("ring"), fill=ON_ACCENT,
                                                font=(FONT, 20, "bold"))
        self.ring.bind("<Button-1>", lambda e: self.run(b"R"))
        self.ring.bind("<Enter>", lambda e: self._paint_ring(hover=True))
        self.ring.bind("<Leave>", lambda e: self._paint_ring(hover=False))

        self.status = tk.Label(root, text="", fg=SUB, bg=BG, font=(FONT, 10), wraplength=self.px(280))
        self.status.pack()

        row = tk.Frame(root, bg=BG)
        row.pack(pady=(self.px(14), 0))
        self._button(row, tr("stop"), lambda: self.run(b"S")).pack(side="left", padx=self.px(4))
        self._button(row, tr("pair"), self.pair).pack(side="left", padx=self.px(4))

        self.log_toggle = tk.Label(root, text=tr("show_log"), fg=SUB, bg=BG, font=(FONT, 9), cursor="hand2")
        self.log_toggle.pack(pady=(self.px(14), self.px(12)))
        self.log_toggle.bind("<Button-1>", lambda e: self.toggle_log())

        self.log_box = tk.Text(root, width=44, height=9, bg=CARD, fg=SUB, relief="flat", font=("Consolas", 9),
                               state="disabled", wrap="word", padx=self.px(8), pady=self.px(6))
        root.bind("<Return>", lambda e: self.run(b"R"))
        root.bind("<Escape>", lambda e: self.run(b"S"))
        root.minsize(self.px(320), 0)

    def _button(self, parent, label, command):
        return tk.Button(parent, text=label, command=command, bg=CARD_HIGH, fg=TEXT, activebackground=CARD,
                         activeforeground=TEXT, relief="flat", bd=0, font=(FONT, 10), width=12, pady=self.px(6),
                         cursor="hand2")

    def _paint_ring(self, hover=False):
        color = ACCENT_BUSY if self.busy else ACCENT_HOVER if hover else ACCENT
        self.ring.itemconfig(self.ring_circle, fill=color)
        self.ring.itemconfig(self.ring_label, text="…" if self.busy else tr("ring"))

    def show(self, msg, color=SUB):
        self.status.config(text=msg, fg=color)

    # ---- ログ ----

    def log(self, msg):
        """どのスレッドからでも呼べるログ出力。"""
        self.root.after(0, self._append_log, time.strftime("%H:%M:%S ") + msg + "\n")

    def _append_log(self, line):
        self.log_box.config(state="normal")
        self.log_box.insert("end", line)
        if int(self.log_box.index("end-1c").split(".")[0]) > 300:
            self.log_box.delete("1.0", "50.0")
        self.log_box.see("end")
        self.log_box.config(state="disabled")

    def toggle_log(self):
        self.log_visible = not self.log_visible
        if self.log_visible:
            self.log_box.pack(fill="both", expand=True, padx=self.px(10), pady=(0, self.px(10)))
        else:
            self.log_box.pack_forget()
        self.log_toggle.config(text=tr("hide_log" if self.log_visible else "show_log"))

    # ---- 接続状態(スマホとつながっているかを常に表示) ----

    def _watch_link(self):
        fails = 0
        while True:
            key = self.key
            ip = None
            if key is not None and not self.busy:
                # 普段は軽い探索だけ。失敗が続いた時だけ全端末へ送る
                sweep_after = 0.0 if fails and fails % SWEEP_EVERY_FAILS == 0 else None
                notes = []  # 「鍵が違う」応答があった時だけメッセージが入る
                ip = find_phone(key, None, timeout=2.0, last_ip=self.last_ip, sweep_after=sweep_after,
                                log=notes.append)
                fails = 0 if ip else fails + 1
                if ip:
                    self._remember(ip)
                state = ip or ("wrong-key" if notes else "none")
                if state != self._link_state:  # 状態が変わった時だけログに出す
                    self._link_state = state
                    self.log(tr("log_link_ok", ip=ip) if ip else notes[0] if notes else tr("log_link_ng"))
            if not self.busy:
                self.root.after(0, self._set_link, ip)
            time.sleep(LINK_CHECK_INTERVAL)

    def _set_link(self, ip):
        if self.key is None:
            self.link.config(text="● " + tr("link_unpaired"), fg=SUB)
        elif ip:
            self.link.config(text="● " + tr("link_ok", ip=ip), fg=ACCENT)
        elif self._link_state is None:
            self.link.config(text="● " + tr("link_checking"), fg=SUB)
        else:
            self.link.config(text="● " + tr("link_ng"), fg=BAD)

    def _remember(self, ip):
        if ip != self.last_ip:
            self.last_ip = ip
            save_last_ip(ip)

    # ---- 鳴らす / 止める ----

    def run(self, cmd):
        if self.busy:
            return
        if self.key is None:
            self.pair()
            return
        self.busy = True
        self._paint_ring()
        self.show(tr("msg_searching"))
        threading.Thread(target=self._work, args=(cmd,), daemon=True).start()

    def _work(self, cmd):
        self.log(tr("ring") if cmd == b"R" else tr("stop"))
        ip = find_phone(self.key, cmd, last_ip=self.last_ip, log=self.log)
        if ip:
            self._remember(ip)
        self.root.after(0, self._done, cmd, ip)

    def _done(self, cmd, ip):
        self.busy = False
        self._paint_ring()
        if ip:
            self._link_state = ip
            self._set_link(ip)
            self.show(tr("msg_ringing") if cmd == b"R" else tr("msg_stopped"), ACCENT)
        else:
            self.show(tr("msg_not_found"), BAD)

    # ---- ペアリング ----

    def pair(self):
        dialog = tk.Toplevel(self.root)
        dialog.title(tr("pair"))
        dialog.configure(bg=BG)
        dialog.resizable(False, False)
        dialog.transient(self.root)

        tk.Label(dialog, text=tr("pair_prompt"), fg=TEXT, bg=BG, font=(FONT, 10)).pack(
            padx=self.px(18), pady=(self.px(16), self.px(8)))
        entry = tk.Entry(dialog, width=36, font=("Consolas", 11), bg=CARD, fg=TEXT, insertbackground=TEXT,
                         relief="flat", justify="center")
        entry.pack(padx=self.px(18), ipady=self.px(6))
        error = tk.Label(dialog, text="", fg=BAD, bg=BG, font=(FONT, 9))
        error.pack(pady=(self.px(4), 0))

        clip = self._clipboard()
        clip_is_code = self._valid_code(clip)
        if clip_is_code:  # スマホの「共有」で届いたコードをコピーしてあれば、自動で入力する
            entry.insert(0, clip.strip())

        def accept(_event=None):
            try:
                key = parse_code(entry.get())
            except ValueError:
                error.config(text=tr("pair_bad"))
                return
            try:
                save_key(key)
            except OSError as e:
                error.config(text=tr("pair_save_failed", e=e))
                return
            self.key = key
            self._link_state = None
            if clip_is_code:
                self.root.clipboard_clear()  # 秘密のコードをクリップボードに残さない
            dialog.destroy()
            self.show(tr("msg_paired"), ACCENT)
            self.log(tr("msg_paired"))
            self._set_link(None)

        row = tk.Frame(dialog, bg=BG)
        row.pack(pady=self.px(14))
        self._button(row, tr("ok"), accept).pack(side="left", padx=self.px(4))
        self._button(row, tr("cancel"), dialog.destroy).pack(side="left", padx=self.px(4))
        entry.bind("<Return>", accept)
        dialog.bind("<Escape>", lambda e: dialog.destroy())
        dialog.grab_set()
        entry.focus_set()
        return dialog

    def _clipboard(self) -> str:
        try:
            return self.root.clipboard_get()
        except tk.TclError:
            return ""

    @staticmethod
    def _valid_code(text: str) -> bool:
        try:
            parse_code(text)
            return True
        except ValueError:
            return False

    def start(self):
        self.root.mainloop()


def _selftest() -> int:
    """ビルド後の動作確認: 画面を一通り組み立てて閉じる(CIで実行)。"""
    try:
        code = base64.b32encode(bytes(range(16))).decode().rstrip("=")
        assert parse_code(code.lower()) == bytes(range(16))
        assert len(mac(b"k", b"a", b"b")) == 32
        app = App()
        app.toggle_log()
        app.pair().destroy()
        app.root.update()
        app.root.destroy()
        return 0
    except BaseException:
        return 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        os._exit(_selftest())
    _enable_hidpi()
    if not _already_running():
        App().start()
