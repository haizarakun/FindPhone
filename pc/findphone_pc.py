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
import json
import os
import select
import socket
import subprocess
import sys
import threading
import time

try:
    import tkinter as tk
except ImportError:  # 画面なしの環境(通信部分のテスト用)
    tk = None
try:
    import qrcode
except ImportError:  # 無くても動く(QRでのペアリングだけ使えなくなる)
    qrcode = None

APP_NAME = "FindPhone"
VERSION = "1.00"
PORT = 47321
APP_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), APP_NAME)
KEY_FILE = os.path.join(APP_DIR, "key.bin")
LAST_IP_FILE = os.path.join(APP_DIR, "last_ip.txt")
LANG_FILE = os.path.join(APP_DIR, "lang.txt")
AUTOSTART_FLAG_FILE = os.path.join(APP_DIR, "autostart_configured.txt")
LATEST_RELEASE_API = "https://api.github.com/repos/haizarakun/FindPhone/releases/latest"
QR_PREFIX = "FINDPHONE1:"  # スマホアプリが読み取るQRコードの先頭

LINK_CHECK_INTERVAL = 3.0   # 接続確認の間隔(秒)
SWEEP_EVERY_FAILS = 5       # 接続確認がこの回数続けて失敗したら、全端末へ探索を送る


# ---- 表示テキスト(10言語。ログは日本語と英語のみで、他の言語では英語になる) ----

DEFAULT_LANGUAGE = "en"
LANGUAGES = {  # 言語コード -> (言語名, 文字が正しく出るフォント)
    "en": ("English", "Segoe UI"),
    "ja": ("日本語", "Yu Gothic UI"),
    "zh-CN": ("简体中文", "Microsoft YaHei UI"),
    "zh-TW": ("繁體中文", "Microsoft JhengHei UI"),
    "ko": ("한국어", "Malgun Gothic"),
    "es": ("Español", "Segoe UI"),
    "fr": ("Français", "Segoe UI"),
    "de": ("Deutsch", "Segoe UI"),
    "pt": ("Português", "Segoe UI"),
    "ru": ("Русский", "Segoe UI"),
}

_UI_KEYS = ("title ring stop pair show_log hide_log language link_checking link_ok link_ng link_unpaired "
            "msg_ready msg_unpaired msg_searching msg_ringing msg_stopped msg_not_found msg_paired "
            "pair_prompt pair_bad pair_save_failed ok cancel "
            "show_qr qr_hint qr_waiting update_available updating update_failed "
            "tray_open autostart quit").split()

_UI = {
    "ja": "スマホ探し|鳴らす|止める|ペアリング|ログを表示|ログを隠す|言語|確認中…|接続中  {ip}|未接続|未ペアリング|"
          "ボタンを押すとスマホが鳴ります|最初にペアリングしてください|探しています…|鳴らしています|止めました|"
          "見つかりません。スマホの「ヘルプ」で診断できます|ペアリングしました|"
          "スマホに表示されたペアリングコードを入力|コードが正しくありません|鍵を保存できません: {e}|OK|キャンセル",
    "en": "Find Phone|Ring|Stop|Pair|Show log|Hide log|Language|Checking…|Connected  {ip}|Not connected|Not paired|"
          "Press the button to ring your phone|Pair with your phone first|Searching…|Ringing|Stopped|"
          "Not found. Open Help on the phone to diagnose|Paired|"
          "Enter the pairing code shown on the phone|The code is not valid|Could not save the key: {e}|OK|Cancel",
    "zh-CN": "找手机|响铃|停止|配对|显示日志|隐藏日志|语言|检查中…|已连接  {ip}|未连接|未配对|"
             "按下按钮即可让手机响铃|请先与手机配对|正在查找…|正在响铃|已停止|"
             "未找到。可在手机的“帮助”中诊断|配对成功|"
             "输入手机上显示的配对码|配对码无效|无法保存密钥：{e}|确定|取消",
    "zh-TW": "找手機|響鈴|停止|配對|顯示記錄|隱藏記錄|語言|檢查中…|已連線  {ip}|未連線|未配對|"
             "按下按鈕即可讓手機響鈴|請先與手機配對|正在尋找…|正在響鈴|已停止|"
             "找不到。可在手機的「說明」中診斷|配對成功|"
             "輸入手機上顯示的配對碼|配對碼無效|無法儲存金鑰：{e}|確定|取消",
    "ko": "폰 찾기|울리기|중지|페어링|로그 보기|로그 숨기기|언어|확인 중…|연결됨  {ip}|연결 안 됨|페어링 안 됨|"
          "버튼을 누르면 폰이 울립니다|먼저 폰과 페어링하세요|찾는 중…|울리는 중|중지했습니다|"
          "찾을 수 없습니다. 폰의 '도움말'에서 진단할 수 있습니다|페어링했습니다|"
          "폰에 표시된 페어링 코드를 입력하세요|코드가 올바르지 않습니다|키를 저장할 수 없습니다: {e}|확인|취소",
    "es": "Buscar teléfono|Sonar|Detener|Vincular|Mostrar registro|Ocultar registro|Idioma|Comprobando…|"
          "Conectado  {ip}|Sin conexión|Sin vincular|"
          "Pulsa el botón para hacer sonar tu teléfono|Primero vincula tu teléfono|Buscando…|Sonando|Detenido|"
          "No encontrado. Abre Ayuda en el teléfono para diagnosticar|Vinculado|"
          "Introduce el código de vinculación del teléfono|El código no es válido|No se pudo guardar la clave: {e}|"
          "Aceptar|Cancelar",
    "fr": "Trouver le téléphone|Sonner|Arrêter|Associer|Afficher le journal|Masquer le journal|Langue|Vérification…|"
          "Connecté  {ip}|Non connecté|Non associé|"
          "Appuyez sur le bouton pour faire sonner votre téléphone|Associez d'abord votre téléphone|Recherche…|"
          "Sonnerie en cours|Arrêté|"
          "Introuvable. Ouvrez l'Aide sur le téléphone pour diagnostiquer|Associé|"
          "Saisissez le code d'association affiché sur le téléphone|Le code n'est pas valide|"
          "Impossible d'enregistrer la clé : {e}|OK|Annuler",
    "de": "Handy finden|Klingeln|Stopp|Koppeln|Protokoll anzeigen|Protokoll verbergen|Sprache|Prüfe…|"
          "Verbunden  {ip}|Nicht verbunden|Nicht gekoppelt|"
          "Drücke den Knopf, um dein Handy klingeln zu lassen|Kopple zuerst dein Handy|Suche…|Klingelt|Gestoppt|"
          "Nicht gefunden. Öffne die Hilfe auf dem Handy zur Diagnose|Gekoppelt|"
          "Gib den Kopplungscode vom Handy ein|Der Code ist ungültig|Schlüssel konnte nicht gespeichert werden: {e}|"
          "OK|Abbrechen",
    "pt": "Encontrar telefone|Tocar|Parar|Parear|Mostrar registro|Ocultar registro|Idioma|Verificando…|"
          "Conectado  {ip}|Sem conexão|Não pareado|"
          "Pressione o botão para fazer o telefone tocar|Primeiro pareie com o telefone|Procurando…|Tocando|Parado|"
          "Não encontrado. Abra a Ajuda no telefone para diagnosticar|Pareado|"
          "Digite o código de pareamento mostrado no telefone|O código não é válido|"
          "Não foi possível salvar a chave: {e}|OK|Cancelar",
    "ru": "Поиск телефона|Позвонить|Стоп|Сопряжение|Показать журнал|Скрыть журнал|Язык|Проверка…|"
          "Подключено  {ip}|Нет связи|Нет сопряжения|"
          "Нажмите кнопку, чтобы телефон зазвонил|Сначала выполните сопряжение с телефоном|Поиск…|Звонит|Остановлено|"
          "Не найден. Откройте «Справку» на телефоне для диагностики|Сопряжено|"
          "Введите код сопряжения с экрана телефона|Неверный код|Не удалось сохранить ключ: {e}|ОК|Отмена",
}
_UI_MORE = {  # QRペアリングとアップデートの文言
    "ja": "QRコードを表示|スマホアプリの「PCのQRコードを読み取る」で読み取ってください|スマホを待っています…|v{v} に更新|更新しています…|更新に失敗しました",
    "en": "Show QR code|Scan it with “Scan the QR code on the PC” in the phone app|Waiting for the phone…|Update to v{v}|Updating…|Update failed",
    "zh-CN": "显示二维码|请用手机应用的“扫描电脑上的二维码”扫描|正在等待手机…|更新到 v{v}|正在更新…|更新失败",
    "zh-TW": "顯示 QR 碼|請用手機應用程式的「掃描電腦上的 QR 碼」掃描|正在等待手機…|更新至 v{v}|正在更新…|更新失敗",
    "ko": "QR 코드 표시|폰 앱의 ‘PC의 QR 코드 스캔’으로 스캔하세요|폰을 기다리는 중…|v{v}(으)로 업데이트|업데이트 중…|업데이트에 실패했습니다",
    "es": "Mostrar código QR|Escanéalo con «Escanear el código QR del PC» en la app del teléfono|Esperando al teléfono…|Actualizar a v{v}|Actualizando…|Error al actualizar",
    "fr": "Afficher le code QR|Scannez-le avec « Scanner le code QR du PC » dans l’application du téléphone|En attente du téléphone…|Mettre à jour vers v{v}|Mise à jour…|Échec de la mise à jour",
    "de": "QR-Code anzeigen|Scanne ihn mit „QR-Code am PC scannen“ in der Handy-App|Warte auf das Handy…|Auf v{v} aktualisieren|Aktualisiere…|Update fehlgeschlagen",
    "pt": "Mostrar código QR|Leia com “Ler o código QR do PC” no app do telefone|Aguardando o telefone…|Atualizar para v{v}|Atualizando…|Falha na atualização",
    "ru": "Показать QR-код|Отсканируйте его через «Сканировать QR-код на ПК» в приложении на телефоне|Ожидание телефона…|Обновить до v{v}|Обновление…|Не удалось обновить",
}
_UI_TRAY = {  # 通知領域(タスクバー右下)のメニュー
    "ja": "開く|Windowsの起動時に開始|終了",
    "en": "Open|Start with Windows|Exit",
    "zh-CN": "打开|随 Windows 启动|退出",
    "zh-TW": "開啟|隨 Windows 啟動|結束",
    "ko": "열기|Windows 시작 시 실행|종료",
    "es": "Abrir|Iniciar con Windows|Salir",
    "fr": "Ouvrir|Démarrer avec Windows|Quitter",
    "de": "Öffnen|Mit Windows starten|Beenden",
    "pt": "Abrir|Iniciar com o Windows|Sair",
    "ru": "Открыть|Запускать вместе с Windows|Выход",
}
TEXTS = {code: dict(zip(_UI_KEYS, "|".join((blob, _UI_MORE[code], _UI_TRAY[code])).split("|")))
         for code, blob in _UI.items()}
assert all(len(v) == len(_UI_KEYS) for v in TEXTS.values())

TEXTS["ja"].update({
    "log_start": "起動 v{v}",
    "log_no_lan": "LANのIPアドレスが見つかりません(ネットワーク未接続?)",
    "log_from": "送信元: {ips}",
    "log_wrong_key": "{ip} が応答しましたが鍵が違います。再ペアリングしてください",
    "log_found": "スマホ発見: {ip}",
    "log_sent": "命令を送信",
    "log_ack": "スマホが命令を受理",
    "log_timeout": "{sec:.0f}秒以内に応答なし",
    "log_link_ok": "接続確認: 接続中 {ip}",
    "log_link_ng": "接続確認: 応答なし",
})
TEXTS["en"].update({
    "log_start": "Started v{v}",
    "log_no_lan": "No LAN address found (network disconnected?)",
    "log_from": "Sending from: {ips}",
    "log_wrong_key": "{ip} answered with a different key. Pair again",
    "log_found": "Phone found: {ip}",
    "log_sent": "Command sent",
    "log_ack": "Phone accepted the command",
    "log_timeout": "No answer within {sec:.0f} s",
    "log_link_ok": "Link check: connected {ip}",
    "log_link_ng": "Link check: no answer",
})


def load_language() -> str:
    """設定で選んだ言語。未設定なら英語。"""
    try:
        with open(LANG_FILE, encoding="ascii") as f:
            code = f.read().strip()
        if code in LANGUAGES:
            return code
    except OSError:
        pass
    return DEFAULT_LANGUAGE


def save_language(code: str) -> None:
    try:
        os.makedirs(APP_DIR, exist_ok=True)
        with open(LANG_FILE, "w", encoding="ascii") as f:
            f.write(code)
    except OSError:
        pass


_lang = load_language()


def set_language(code: str) -> None:
    global _lang
    _lang = code
    save_language(code)


def tr(key: str, **values) -> str:
    text = TEXTS[_lang].get(key) or TEXTS["en"][key]
    return text.format(**values)


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


# ---- アップデート ----
# HTTPSの通信は Windows 標準の curl.exe に任せる(exeにOpenSSLを同梱せずに済み、証明書の検証もOS任せにできる)。

def _curl(*args, timeout=60) -> bytes:
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)  # 黒い窓を出さない
    exe = "curl.exe" if os.name == "nt" else "curl"
    done = subprocess.run([exe, "-sSL", "--fail", "--proto", "=https", "--max-time", str(timeout),
                           "-A", "FindPhone-PC", *args],
                          capture_output=True, timeout=timeout + 10, creationflags=flags)
    if done.returncode != 0:
        raise OSError(f"curl failed ({done.returncode})")
    return done.stdout


def is_newer(candidate: str, current: str) -> bool:
    """ "1.01" と "1.00" のようなバージョンを、区切りごとの数値で比べる。"""
    def numbers(v):
        return [int(part) if part.isdigit() else 0 for part in v.split(".")]
    a, b = numbers(candidate), numbers(current)
    width = max(len(a), len(b))
    return a + [0] * (width - len(a)) > b + [0] * (width - len(b))


def check_update():
    """新しい版があれば (バージョン, exeのURL, SHA-256) を返す。無ければ None。"""
    try:
        release = json.loads(_curl(LATEST_RELEASE_API, timeout=15))
        version = release["tag_name"].lstrip("v")
        if not is_newer(version, VERSION):
            return None
        for asset in release["assets"]:
            digest = asset.get("digest") or ""
            url = asset.get("browser_download_url", "")
            # 照合用のSHA-256が公開されているexeだけを対象にする
            if asset["name"].endswith(".exe") and url.startswith("https://github.com/") \
                    and digest.startswith("sha256:"):
                return version, url, digest[7:].lower()
    except Exception:
        pass  # オフライン・未公開などは「更新なし」として扱う
    return None


def apply_update(url: str, sha256: str, before_restart=None) -> None:
    """新しいexeをダウンロードして照合し、今のexeと入れ替えて起動し直す。失敗時は例外。"""
    if not getattr(sys, "frozen", False):
        raise OSError("not running as exe")
    exe = sys.executable
    new, old = exe + ".new", exe + ".old"
    data = _curl(url, timeout=300)
    if hashlib.sha256(data).hexdigest() != sha256:
        raise OSError("checksum mismatch")
    with open(new, "wb") as f:
        f.write(data)
    if os.path.exists(old):
        os.remove(old)
    os.replace(exe, old)  # 実行中のexeは消せないが、名前の変更はできる
    os.replace(new, exe)
    if before_restart:
        before_restart()
    subprocess.Popen([exe, "--updated"], close_fds=True)
    os._exit(0)


def _remove_old_exe() -> None:
    """アップデートで残った1つ前のexeを消す。"""
    if getattr(sys, "frozen", False):
        try:
            os.remove(sys.executable + ".old")
        except OSError:
            pass


# ---- Windows向けの下ごしらえ ----

def _resource(name: str) -> str:
    """同梱ファイルの場所(exe化した時は展開先、スクリプト実行時は同じフォルダ)。"""
    return os.path.join(getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))), name)


def _already_running() -> bool:
    """二重起動なら、既に動いている方へ「ウィンドウを開け」と伝えて True を返す。"""
    try:
        import ctypes
        handle = ctypes.windll.kernel32.CreateMutexW(None, False, "Local\\FindPhonePC")
        if ctypes.windll.kernel32.GetLastError() != 183:  # ERROR_ALREADY_EXISTS
            return False
        ctypes.windll.kernel32.CloseHandle(handle)
        hwnd = ctypes.windll.user32.FindWindowW(Tray.CLASS_NAME, None)
        if hwnd:
            ctypes.windll.user32.PostMessageW(hwnd, Tray.MSG_SHOW, 0, 0)
        return True
    except Exception:
        return False


# ---- Windowsの起動時に自動で開始 ----

_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def autostart_enabled() -> bool:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY) as key:
            winreg.QueryValueEx(key, APP_NAME)
        return True
    except (ImportError, OSError):
        return False


def set_autostart(enabled: bool) -> None:
    """ログイン時の自動起動を登録/解除する(現在のユーザーのみ。管理者権限は不要)。"""
    if not getattr(sys, "frozen", False):
        return  # exeとして動いている時だけ登録する
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enabled:  # --tray: ウィンドウを出さず、通知領域に入った状態で起動する
                winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, f'"{sys.executable}" --tray')
            else:
                winreg.DeleteValue(key, APP_NAME)
    except (ImportError, OSError):
        pass


def setup_autostart() -> None:
    """初回だけ自動起動をオンにする。以降は利用者の選択を尊重し、exeを移動していたら場所だけ更新する。"""
    if not os.path.exists(AUTOSTART_FLAG_FILE):
        set_autostart(True)
        try:
            os.makedirs(APP_DIR, exist_ok=True)
            with open(AUTOSTART_FLAG_FILE, "w", encoding="ascii") as f:
                f.write("1")
        except OSError:
            pass
    elif autostart_enabled():
        set_autostart(True)


# ---- 通知領域(タスクバー右下)のアイコン ----

class Tray:
    """通知領域のアイコン。専用スレッドでWindowsのメッセージを受け取り、操作をコールバックで知らせる。

    on_open: アイコンをクリックした時 / build_menu: 右クリック時に [(ID, 表示名, チェック有無)] を返す /
    on_command: メニューで選ばれたID。いずれもこのスレッドから呼ばれるので、画面操作は呼ばれた側で
    メインスレッドへ渡すこと。
    """
    CLASS_NAME = "FindPhoneTrayWindow"
    MSG_TRAY = 0x8001  # アイコンの操作
    MSG_SHOW = 0x8002  # 二重起動した側からの「ウィンドウを開け」

    def __init__(self, icon_path, tooltip, on_open, build_menu, on_command):
        self.icon_path, self.tooltip = icon_path, tooltip
        self.on_open, self.build_menu, self.on_command = on_open, build_menu, on_command
        self.ok = False  # アイコンを出せたか(出せない環境では通常のウィンドウとして動く)
        self._ready = threading.Event()
        self._remove = lambda: None
        threading.Thread(target=self._run, daemon=True).start()
        self._ready.wait(3)

    def remove(self):
        self._remove()

    def _run(self):
        try:
            self._loop()
        except Exception:
            self.ok = False
        finally:
            self._ready.set()

    def _loop(self):
        import ctypes
        from ctypes import wintypes as w

        user32, shell32, kernel32 = ctypes.WinDLL("user32"), ctypes.WinDLL("shell32"), ctypes.WinDLL("kernel32")
        LRESULT = ctypes.c_ssize_t
        WNDPROC = ctypes.WINFUNCTYPE(LRESULT, w.HWND, w.UINT, w.WPARAM, w.LPARAM)

        class WNDCLASSW(ctypes.Structure):
            _fields_ = [("style", w.UINT), ("lpfnWndProc", WNDPROC), ("cbClsExtra", ctypes.c_int),
                        ("cbWndExtra", ctypes.c_int), ("hInstance", w.HINSTANCE), ("hIcon", w.HICON),
                        ("hCursor", w.HANDLE), ("hbrBackground", w.HANDLE), ("lpszMenuName", w.LPCWSTR),
                        ("lpszClassName", w.LPCWSTR)]

        class NOTIFYICONDATAW(ctypes.Structure):
            _fields_ = [("cbSize", w.DWORD), ("hWnd", w.HWND), ("uID", w.UINT), ("uFlags", w.UINT),
                        ("uCallbackMessage", w.UINT), ("hIcon", w.HICON), ("szTip", w.WCHAR * 128),
                        ("dwState", w.DWORD), ("dwStateMask", w.DWORD), ("szInfo", w.WCHAR * 256),
                        ("uVersion", w.UINT), ("szInfoTitle", w.WCHAR * 64), ("dwInfoFlags", w.DWORD),
                        ("guidItem", ctypes.c_byte * 16), ("hBalloonIcon", w.HICON)]

        # 64bitでハンドルが壊れないよう、使う関数の引数と戻り値の型を明示する
        kernel32.GetModuleHandleW.restype, kernel32.GetModuleHandleW.argtypes = w.HMODULE, [w.LPCWSTR]
        user32.DefWindowProcW.restype = LRESULT
        user32.DefWindowProcW.argtypes = [w.HWND, w.UINT, w.WPARAM, w.LPARAM]
        user32.RegisterClassW.argtypes = [ctypes.POINTER(WNDCLASSW)]
        user32.CreateWindowExW.restype = w.HWND
        user32.CreateWindowExW.argtypes = [w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int,
                                           ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, w.LPVOID]
        user32.LoadImageW.restype = w.HANDLE
        user32.LoadImageW.argtypes = [w.HINSTANCE, w.LPCWSTR, w.UINT, ctypes.c_int, ctypes.c_int, w.UINT]
        user32.CreatePopupMenu.restype = w.HMENU
        user32.AppendMenuW.argtypes = [w.HMENU, w.UINT, ctypes.c_size_t, w.LPCWSTR]
        user32.TrackPopupMenu.argtypes = [w.HMENU, w.UINT, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HWND,
                                          w.LPVOID]
        user32.DestroyMenu.argtypes = [w.HMENU]
        user32.SetForegroundWindow.argtypes = [w.HWND]
        user32.GetCursorPos.argtypes = [ctypes.POINTER(w.POINT)]
        user32.GetMessageW.argtypes = [ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT]
        user32.DispatchMessageW.argtypes = [ctypes.POINTER(w.MSG)]
        user32.TranslateMessage.argtypes = [ctypes.POINTER(w.MSG)]
        shell32.Shell_NotifyIconW.argtypes = [w.DWORD, ctypes.POINTER(NOTIFYICONDATAW)]

        WM_LBUTTONUP, WM_LBUTTONDBLCLK, WM_RBUTTONUP = 0x0202, 0x0203, 0x0205
        MF_STRING, MF_CHECKED = 0x0000, 0x0008
        TPM_RIGHTBUTTON, TPM_RETURNCMD = 0x0002, 0x0100

        def show_menu(hwnd):
            point = w.POINT()
            user32.GetCursorPos(ctypes.byref(point))
            menu = user32.CreatePopupMenu()
            for item_id, label, checked in self.build_menu():
                user32.AppendMenuW(menu, MF_STRING | (MF_CHECKED if checked else 0), item_id, label)
            user32.SetForegroundWindow(hwnd)  # これが無いと、メニューの外を押しても閉じない
            chosen = user32.TrackPopupMenu(menu, TPM_RIGHTBUTTON | TPM_RETURNCMD, point.x, point.y, 0, hwnd, None)
            user32.DestroyMenu(menu)
            if chosen:
                self.on_command(chosen)

        def window_proc(hwnd, message, wparam, lparam):
            try:
                if message == Tray.MSG_SHOW or (message == Tray.MSG_TRAY
                                                and lparam in (WM_LBUTTONUP, WM_LBUTTONDBLCLK)):
                    self.on_open()
                    return 0
                if message == Tray.MSG_TRAY and lparam == WM_RBUTTONUP:
                    show_menu(hwnd)
                    return 0
            except Exception:
                pass  # コールバックの失敗でメッセージ処理を止めない
            return user32.DefWindowProcW(hwnd, message, wparam, lparam)

        self._window_proc = WNDPROC(window_proc)  # 回収されないよう参照を持ち続ける
        instance = kernel32.GetModuleHandleW(None)
        window_class = WNDCLASSW()
        window_class.lpfnWndProc = self._window_proc
        window_class.hInstance = instance
        window_class.lpszClassName = Tray.CLASS_NAME
        user32.RegisterClassW(ctypes.byref(window_class))
        hwnd = user32.CreateWindowExW(0, Tray.CLASS_NAME, APP_NAME, 0, 0, 0, 0, 0, None, None, instance, None)
        if not hwnd:
            return

        IMAGE_ICON, LR_LOADFROMFILE, LR_DEFAULTSIZE = 1, 0x0010, 0x0040
        icon = NOTIFYICONDATAW()
        icon.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
        icon.hWnd = hwnd
        icon.uID = 1
        icon.uFlags = 0x1 | 0x2 | 0x4  # NIF_MESSAGE | NIF_ICON | NIF_TIP
        icon.uCallbackMessage = Tray.MSG_TRAY
        icon.hIcon = user32.LoadImageW(None, self.icon_path, IMAGE_ICON, 0, 0, LR_LOADFROMFILE | LR_DEFAULTSIZE)
        icon.szTip = self.tooltip[:127]
        self.ok = bool(shell32.Shell_NotifyIconW(0, ctypes.byref(icon)))  # NIM_ADD
        self._remove = lambda: shell32.Shell_NotifyIconW(2, ctypes.byref(icon))  # NIM_DELETE
        self._ready.set()

        message = w.MSG()
        while user32.GetMessageW(ctypes.byref(message), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(message))
            user32.DispatchMessageW(ctypes.byref(message))


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
FONT = "Segoe UI"  # 画面を組み立てる時に、選んだ言語のフォントへ差し替える
BAD = "#FF5252"


class App:
    # 通知領域メニューの項目ID
    MENU_OPEN, MENU_RING, MENU_STOP, MENU_AUTOSTART, MENU_QUIT = 1, 2, 3, 4, 5

    def __init__(self, start_in_tray=False):
        self.root = tk.Tk()
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
        self.update = None  # 新しい版が見つかったら (バージョン, URL, SHA-256)

        self._build()
        self.log(tr("log_start", v=VERSION))
        self._set_link(None)
        self.tray = self._create_tray()
        if self.tray and self.tray.ok:
            self.root.protocol("WM_DELETE_WINDOW", self.root.withdraw)  # 閉じるボタンは通知領域へしまう
            if start_in_tray:
                self.root.withdraw()
        threading.Thread(target=self._watch_link, daemon=True).start()
        threading.Thread(target=self._check_update, daemon=True).start()

    # ---- 通知領域 ----

    def _create_tray(self):
        if os.name != "nt":
            return None
        # Tray のコールバックは別スレッドから来るので、after でメインスレッドへ渡す
        return Tray(_resource("icon.ico"), APP_NAME,
                    on_open=lambda: self.root.after(0, self.show_window),
                    build_menu=self._tray_menu,
                    on_command=lambda item: self.root.after(0, self._tray_command, item))

    def _tray_menu(self):
        return [(self.MENU_OPEN, tr("tray_open"), False),
                (self.MENU_RING, tr("ring"), False),
                (self.MENU_STOP, tr("stop"), False),
                (self.MENU_AUTOSTART, tr("autostart"), autostart_enabled()),
                (self.MENU_QUIT, tr("quit"), False)]

    def _tray_command(self, item):
        if item == self.MENU_OPEN:
            self.show_window()
        elif item == self.MENU_RING:
            self.run(b"R")
        elif item == self.MENU_STOP:
            self.run(b"S")
        elif item == self.MENU_AUTOSTART:
            set_autostart(not autostart_enabled())
        elif item == self.MENU_QUIT:
            self.quit()

    def show_window(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def quit(self):
        if self.tray:
            self.tray.remove()
        self.root.destroy()

    def px(self, value: float) -> int:
        return round(value * self.scale)

    def _build(self):
        """現在の言語で画面を組み立てる(言語を切り替えた時は作り直す)。"""
        global FONT
        FONT = LANGUAGES[_lang][1]
        root = self.root
        root.title(tr("title"))
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

        self.update_link = tk.Label(root, text="", fg=ACCENT, bg=BG, font=(FONT, 10, "bold"), cursor="hand2")
        self.update_link.bind("<Button-1>", lambda e: self._start_update())
        self.links = links = tk.Frame(root, bg=BG)
        links.pack(pady=(self.px(14), self.px(12)))
        self.log_toggle = tk.Label(links, text=tr("show_log"), fg=SUB, bg=BG, font=(FONT, 9), cursor="hand2")
        self.log_toggle.pack(side="left", padx=self.px(10))
        self.log_toggle.bind("<Button-1>", lambda e: self.toggle_log())
        language = tk.Label(links, text=f"{tr('language')}: {LANGUAGES[_lang][0]}", fg=SUB, bg=BG, font=(FONT, 9),
                            cursor="hand2")
        language.pack(side="left", padx=self.px(10))
        language.bind("<Button-1>", self._pick_language)

        self.log_box = tk.Text(root, width=44, height=9, bg=CARD, fg=SUB, relief="flat", font=("Consolas", 9),
                               state="disabled", wrap="word", padx=self.px(8), pady=self.px(6))
        root.bind("<Return>", lambda e: self.run(b"R"))
        root.bind("<Escape>", lambda e: self.run(b"S"))
        root.minsize(self.px(320), 0)
        self.log_visible = False
        self.show(tr("msg_unpaired") if self.key is None else tr("msg_ready"))
        self._show_update_link()

    # ---- アップデート ----

    def _check_update(self):
        found = check_update()
        if found:
            self.update = found
            self.root.after(0, self._show_update_link)

    def _show_update_link(self):
        if self.update:
            self.update_link.config(text=tr("update_available", v=self.update[0]))
            self.update_link.pack(before=self.links, pady=(self.px(12), 0))

    def _start_update(self):
        if self.busy or not self.update:
            return
        self.busy = True
        self.update_link.config(text=tr("updating"))
        threading.Thread(target=self._update_worker, daemon=True).start()

    def _update_worker(self):
        try:
            apply_update(self.update[1], self.update[2], before_restart=self.tray.remove if self.tray else None)
            # 成功すると上の呼び出しの中で新しい版に切り替わる
        except Exception as e:
            self.log(f"{tr('update_failed')}: {e}")
            self.root.after(0, self._update_failed)

    def _update_failed(self):
        self.busy = False
        self.update_link.config(text=tr("update_failed"))

    # ---- 言語 ----

    def _pick_language(self, event):
        menu = tk.Menu(self.root, tearoff=0, bg=CARD, fg=TEXT, activebackground=CARD_HIGH, activeforeground=TEXT)
        for code, (name, _font) in LANGUAGES.items():
            menu.add_command(label=("● " if code == _lang else "    ") + name,
                             command=lambda c=code: self.change_language(c))
        menu.tk_popup(event.x_root, event.y_root)

    def change_language(self, code):
        if code == _lang or self.busy:
            return
        set_language(code)
        log_text = self.log_box.get("1.0", "end-1c")
        for widget in self.root.winfo_children():
            widget.destroy()
        self._build()
        self._append_log(log_text + "\n" if log_text else "")
        self._set_link(self._link_state if self._link_state not in (None, "none", "wrong-key") else None)

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
            self.show_window()
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
        if qrcode is not None:
            def use_qr():
                dialog.destroy()
                self.pair_by_qr()
            qr_link = tk.Label(dialog, text=tr("show_qr"), fg=ACCENT, bg=BG, font=(FONT, 10), cursor="hand2")
            qr_link.pack(pady=(0, self.px(14)))
            qr_link.bind("<Button-1>", lambda e: use_qr())
        entry.bind("<Return>", accept)
        dialog.bind("<Escape>", lambda e: dialog.destroy())
        dialog.grab_set()
        entry.focus_set()
        return dialog

    def pair_by_qr(self):
        """新しい鍵をQRコードで表示し、スマホが読み取って応答したらその鍵を採用する。"""
        candidate = os.urandom(16)
        code = base64.b32encode(candidate).decode("ascii").rstrip("=")
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4)
        qr.add_data(QR_PREFIX + code)
        qr.make(fit=True)
        matrix = qr.get_matrix()

        dialog = tk.Toplevel(self.root)
        dialog.title(tr("pair"))
        dialog.configure(bg=BG)
        dialog.resizable(False, False)
        dialog.transient(self.root)

        cell = max(2, self.px(240) // len(matrix))
        size = cell * len(matrix)
        canvas = tk.Canvas(dialog, width=size, height=size, bg="white", highlightthickness=0)
        canvas.pack(padx=self.px(18), pady=(self.px(18), self.px(10)))
        for y, line in enumerate(matrix):
            for x, dark in enumerate(line):
                if dark:
                    canvas.create_rectangle(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell,
                                            fill="black", outline="")
        tk.Label(dialog, text=tr("qr_hint"), fg=TEXT, bg=BG, font=(FONT, 10), wraplength=size).pack(
            padx=self.px(18))
        tk.Label(dialog, text=tr("qr_waiting"), fg=SUB, bg=BG, font=(FONT, 9)).pack(pady=(self.px(6), 0))
        self._button(dialog, tr("cancel"), dialog.destroy).pack(pady=self.px(14))
        dialog.bind("<Escape>", lambda e: dialog.destroy())
        dialog.grab_set()

        closed = threading.Event()
        dialog.bind("<Destroy>", lambda e: closed.set() if e.widget is dialog else None)

        def paired(ip):
            if closed.is_set():
                return
            try:
                save_key(candidate)
            except OSError as e:
                self.show(tr("pair_save_failed", e=e), BAD)
                dialog.destroy()
                return
            self.key = candidate
            self._remember(ip)
            self._link_state = ip
            dialog.destroy()
            self.show(tr("msg_paired"), ACCENT)
            self.log(tr("msg_paired"))
            self._set_link(ip)

        def wait_for_phone():  # スマホが新しい鍵で応答するまで探し続ける
            while not closed.is_set():
                ip = find_phone(candidate, None, timeout=2.0, last_ip=self.last_ip, sweep_after=1.0)
                if ip:
                    self.root.after(0, paired, ip)
                    return

        threading.Thread(target=wait_for_phone, daemon=True).start()
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
        for code in LANGUAGES:  # 全言語で画面を組み立てられることを確認
            app.change_language(code)
        app.toggle_log()
        app.pair().destroy()
        if qrcode is not None:
            app.pair_by_qr().destroy()
        assert qrcode is not None, "qrcode module missing from the build"
        assert is_newer("1.02", "1.01") and is_newer("2.0", "1.99") and not is_newer("1.01", "1.01")
        app.root.update()
        if app.tray:  # 通知領域のメニューを組み立てられることを確認(アイコン自体はCIでは出せない場合がある)
            assert len(app._tray_menu()) == 5
        app.quit()
        return 0
    except BaseException:
        return 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        os._exit(_selftest())
    _enable_hidpi()
    _remove_old_exe()
    if "--updated" in sys.argv:  # 更新直後は、古い方のプロセスが終わるまで少し待つ
        for _ in range(50):
            if not _already_running():
                break
            time.sleep(0.1)
        setup_autostart()
        App().start()
    elif not _already_running():
        setup_autostart()
        App(start_in_tray="--tray" in sys.argv).start()
