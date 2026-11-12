"""スマホ探し(PC側): 同じWi-Fi内のスマホを署名付きUDPで鳴らす。"""
import base64
import hashlib
import hmac
import ipaddress
import os
import select
import socket
import threading
import time
import tkinter as tk
from tkinter import messagebox, simpledialog

PORT = 47321
KEY_PATH = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "FindPhone", "key.bin")


# ---- 鍵の保管(WindowsではDPAPIで暗号化) ----

def _dpapi(data: bytes, protect: bool) -> bytes:
    import ctypes
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("n", wintypes.DWORD), ("p", ctypes.POINTER(ctypes.c_char))]

    buf = ctypes.create_string_buffer(data, len(data))
    src = Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    out = Blob()
    fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not fn(ctypes.byref(src), None, None, None, None, 0, ctypes.byref(out)):
        raise OSError("DPAPI failed")
    try:
        return ctypes.string_at(out.p, out.n)
    finally:
        ctypes.windll.kernel32.LocalFree(out.p)


def save_key(key: bytes) -> None:
    os.makedirs(os.path.dirname(KEY_PATH), exist_ok=True)
    blob = _dpapi(key, True) if os.name == "nt" else key
    with open(KEY_PATH, "wb") as f:
        f.write(blob)


def load_key():
    try:
        with open(KEY_PATH, "rb") as f:
            blob = f.read()
        return _dpapi(blob, False) if os.name == "nt" else blob
    except OSError:
        return None


def parse_code(text: str) -> bytes:
    s = "".join(ch for ch in text.upper() if ch.isalnum())
    key = base64.b32decode(s + "=" * (-len(s) % 8))
    if len(key) not in (16, 20):
        raise ValueError("length")
    return key


# ---- 通信 ----

def mac(key: bytes, *parts: bytes) -> bytes:
    return hmac.new(key, b"".join(parts), hashlib.sha256).digest()


def lan_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("10.255.255.255", 1))
        ips.add(probe.getsockname()[0])
    except OSError:
        pass
    finally:
        probe.close()
    return [ip for ip in ips
            if ipaddress.ip_address(ip).is_private and not ip.startswith(("127.", "169.254."))]


def send_command(key: bytes, cmd: bytes, timeout: float = 5.0) -> bool:
    """スマホを探して cmd(b'R'=鳴らす / b'S'=止める) を送り、受理通知まで確認する。
    cmd が None のときは探すだけで、正しい鍵で応答したスマホのIPを返す(接続確認用)。"""
    cn = os.urandom(8)
    socks = []
    for ip in lan_ips():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.bind((ip, 0))
        if hasattr(socket, "SIO_UDP_CONNRESET"):  # Windows: ICMP到達不能で受信が例外になるのを防ぐ
            s.ioctl(socket.SIO_UDP_CONNRESET, False)
        socks.append((ip, s))
    if not socks:
        return False

    try:
        end = time.time() + timeout
        next_hello = 0.0
        waiting_ack = {}  # socket -> (sn, 期限)
        while time.time() < end:
            if time.time() >= next_hello:  # 1秒ごとに再送(UDP取りこぼし対策)
                next_hello = time.time() + 1
                for ip, s in socks:
                    prefix = ip.rsplit(".", 1)[0]
                    # ブロードキャストに加え、同じ /24 の全端末へ直接送る(スリープ中の端末はブロードキャストを捨てることがある)
                    hosts = [f"{prefix}.{n}" for n in range(1, 255) if f"{prefix}.{n}" != ip]
                    for dst in [prefix + ".255", "255.255.255.255"] + hosts:
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
                        continue  # 鍵を知らない相手は無視
                    if cmd is None:
                        return addr[0]
                    s.sendto(cmd + sn + mac(key, cmd, sn), addr)
                    waiting_ack[s] = sn
                elif len(data) == 50 and data[:1] == b"A" and data[1:2] == cmd and s in waiting_ack:
                    sn = waiting_ack[s]
                    if data[2:18] == sn and hmac.compare_digest(data[18:], mac(key, b"A", cmd, sn)):
                        return True
        return False
    finally:
        for _, s in socks:
            s.close()


# ---- GUI ----

class App:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("スマホ探し")
        self.root.geometry("260x260")
        self.root.resizable(False, False)
        self.root.configure(bg="#111111")
        self.key = load_key()

        self.status = tk.Label(self.root, text="", fg="#cccccc", bg="#111111", font=("Meiryo UI", 10))
        self.status.pack(pady=(16, 8))
        self.link = tk.Label(self.root, text="● 確認中…", fg="#888888", bg="#111111", font=("Meiryo UI", 10))
        self.link.pack(pady=(0, 6))
        self.ring_btn = self._button("スマホを鳴らす", lambda: self.run(b"R"), "#2e7d32")
        self.stop_btn = self._button("止める", lambda: self.run(b"S"), "#444444")
        self._button("ペアリング設定", self.pair, "#222222")
        self.show("未ペアリング" if self.key is None else "準備OK")
        threading.Thread(target=self._watch_link, daemon=True).start()

    def _watch_link(self):
        """3秒ごとにスマホへ問い合わせ、リンク状態をリアルタイム表示する。"""
        while True:
            ip = send_command(self.key, None, timeout=2.0) if self.key else None
            self.root.after(0, self._set_link, ip)
            time.sleep(3)

    def _set_link(self, ip):
        if ip:
            self.link.config(text=f"● 接続中  {ip}", fg="#4caf50")
        elif self.key is None:
            self.link.config(text="● 未ペアリング", fg="#888888")
        else:
            self.link.config(text="● 未接続", fg="#e53935")

    def _button(self, label, cmd, bg):
        b = tk.Button(self.root, text=label, command=cmd, bg=bg, fg="white", relief="flat",
                      font=("Meiryo UI", 11), width=20, pady=6)
        b.pack(pady=5)
        return b

    def show(self, msg):
        self.status.config(text=msg)

    def pair(self):
        """クリップボードのコードを優先し、無ければ入力ダイアログで受け取る。"""
        for text in (self._clipboard(), None):
            if text is None:
                text = simpledialog.askstring("ペアリング", "スマホに表示されたコードを入力", parent=self.root)
                if not text:
                    return
            try:
                key = parse_code(text)
            except ValueError:
                continue
            try:
                save_key(key)
            except OSError as e:
                messagebox.showerror("エラー", f"鍵の保存に失敗しました: {e}")
                return
            self.key = key
            self.show("ペアリング完了")
            return
        messagebox.showerror("エラー", "コードが正しくありません(スマホ側アプリが古い可能性)")

    def _clipboard(self):
        try:
            return self.root.clipboard_get()
        except tk.TclError:
            return ""

    def run(self, cmd):
        if self.key is None:
            self.pair()
            if self.key is None:
                return
        self.show("探しています…")
        threading.Thread(target=self._work, args=(cmd,), daemon=True).start()

    def _work(self, cmd):
        ok = send_command(self.key, cmd)
        msg = ("鳴らしています" if cmd == b"R" else "停止しました") if ok else "見つかりません(同じWi-Fi?/コード?)"
        self.root.after(0, self.show, msg)

    def start(self):
        self.root.mainloop()


if __name__ == "__main__":
    App().start()
