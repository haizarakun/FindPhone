"""通信部分のテスト: スマホ役をこのPC内に立てて、探索・鳴動・不正な鍵を確かめる。"""
import os
import socket
import threading

import findphone_pc as fp

fp.PORT = 47399  # 本物のアプリと重ならないテスト用ポート
KEY = bytes(range(16))


def fake_phone(key, received, stop):
    """Android側と同じ応答をする最小のスマホ役。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", fp.PORT))
    s.settimeout(0.2)
    pending = set()
    while not stop.is_set():
        try:
            data, addr = s.recvfrom(128)
        except socket.timeout:
            continue
        if len(data) == 9 and data[:1] == b"H":
            cn, sn = data[1:9], os.urandom(16)
            pending.add(sn)
            s.sendto(b"C" + cn + sn + fp.mac(key, b"C", cn, sn), addr)
        elif len(data) == 49 and data[:1] in (b"R", b"S"):
            cmd, sn = data[:1], data[1:17]
            if data[17:] == fp.mac(key, cmd, sn) and sn in pending:
                pending.discard(sn)  # 1回限り
                received.append(cmd)
                s.sendto(b"A" + cmd + sn + fp.mac(key, b"A", cmd, sn), addr)
    s.close()


def main():
    received, stop = [], threading.Event()
    phone = threading.Thread(target=fake_phone, args=(KEY, received, stop))
    phone.start()
    try:
        local = dict(ips=["127.0.0.1"], last_ip="127.0.0.1", sweep_after=None)
        assert fp.find_phone(KEY, None, timeout=2, **local) == "127.0.0.1", "接続確認"
        assert fp.find_phone(KEY, b"R", timeout=2, **local) == "127.0.0.1", "鳴らす"
        assert fp.find_phone(KEY, b"S", timeout=2, **local) == "127.0.0.1", "止める"
        assert received == [b"R", b"S"], received

        notes = []
        assert fp.find_phone(b"x" * 16, b"R", timeout=1.5, log=notes.append, **local) is None, "違う鍵"
        assert received == [b"R", b"S"], "違う鍵の命令は届かない"
        assert any("127.0.0.1" in n for n in notes), notes

        assert fp.parse_code("aaaa-bbbb cccc dddd eeee ffff gg") and len(fp._targets("10.0.0.5", None, True)) == 255
    finally:
        stop.set()
        phone.join()
    print("ok")


if __name__ == "__main__":
    main()
