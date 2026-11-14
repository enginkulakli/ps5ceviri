"""Real network tests for the Kotlin SubtitleServerCore (runs the compiled Kotlin on a JVM).

Clients connect over the container's non-loopback interface IP to mimic another LAN device.
"""
import asyncio
import json
import socket
import subprocess
import sys
import time

import websockets

JARS = "/opt/jars/Java-WebSocket-1.6.0.jar:/opt/jars/slf4j-api-2.0.13.jar"
CP = f"/tmp/ktbuild/harness.jar:{JARS}:/opt/kt/kotlinc/lib/kotlin-stdlib.jar"
PORT = 8787
results = []


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    finally:
        s.close()


IP = lan_ip()
URL = f"ws://{IP}:{PORT}"


class Harness:
    def __init__(self):
        self.p = subprocess.Popen(["java", "-cp", CP, "HarnessKt"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        assert self.p.stdout.readline().strip() == "READY"

    def cmd(self, c):
        self.p.stdin.write(c + "\n")
        self.p.stdin.flush()
        return self.p.stdout.readline().strip()


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (f" — {detail}" if detail else ""))


async def recv(ws, timeout=2.0):
    return await asyncio.wait_for(ws.recv(), timeout)


async def no_msg(ws, timeout=0.6):
    try:
        await asyncio.wait_for(ws.recv(), timeout)
        return False
    except asyncio.TimeoutError:
        return True


async def main():
    h = Harness()
    check("server starts on 0.0.0.0:8787", h.cmd(f"START {PORT} -") == "START OK")
    check("send with no TV", h.cmd("SEND Merhaba") == "SEND NO_CLIENTS 0")

    ws = await websockets.connect(URL)
    replay = await recv(ws)
    check("TV connecting later gets current subtitle replayed", json.loads(replay) == {"text": "Merhaba"}, replay)
    await asyncio.sleep(0.2)

    r = h.cmd("SEND Tekneye git, çabuk!")
    msg = await recv(ws)
    check("Turkish subtitle delivered", r == "SEND SENT 1" and json.loads(msg) == {"text": "Tekneye git, çabuk!"}, msg)
    check("exact format {\"text\":...} with raw UTF-8", msg == '{"text":"Tekneye git, çabuk!"}', msg)

    check("duplicate not re-sent", h.cmd("SEND Tekneye git, çabuk!") == "SEND DUPLICATE 1" and await no_msg(ws))
    r = h.cmd("FORCE Tekneye git, çabuk!")
    check("forced test re-send works", r == "SEND SENT 1" and json.loads(await recv(ws))["text"] == "Tekneye git, çabuk!")

    r = h.cmd("SENDEMPTY")
    msg = await recv(ws)
    check("empty subtitle {\"text\":\"\"}", r == "SEND SENT 1" and msg == '{"text":""}', msg)

    h.cmd('SEND He said "run" \\ now<NL>line2')
    msg = await recv(ws)
    check("quotes/backslash/newline escaped as valid JSON", json.loads(msg)["text"] == 'He said "run" \\ now\nline2', msg)

    # newest wins ordering
    for i in range(5):
        h.cmd(f"SEND satır {i}")
    got = [json.loads(await recv(ws))["text"] for _ in range(5)]
    check("messages arrive in order (newest last)", got == [f"satır {i}" for i in range(5)], str(got))

    # second device (different source IP: loopback) + disconnect/reconnect
    other = await websockets.connect(f"ws://127.0.0.1:{PORT}")
    await recv(other)  # replay
    await asyncio.sleep(0.2)
    check("two devices counted", "clients=2" in h.cmd("STATE"))
    h.cmd("SEND iki cihaz")
    a, b = json.loads(await recv(ws))["text"], json.loads(await recv(other))["text"]
    check("broadcast to both devices", a == b == "iki cihaz")
    await other.close()
    await asyncio.sleep(0.3)
    check("disconnect detected", "clients=1" in h.cmd("STATE"))
    # TV reconnects from same IP while its old socket is still open (stale) -> replaced
    ws2 = await websockets.connect(URL)
    rep = await recv(ws2)
    check("reconnect gets latest subtitle", json.loads(rep)["text"] == "iki cihaz", rep)
    await asyncio.sleep(0.3)
    st = h.cmd("STATE")
    check("same-IP reconnect replaces stale connection", "clients=1" in st, st)
    try:
        await asyncio.wait_for(ws.recv(), 1)
        old_closed = False
    except websockets.ConnectionClosed:
        old_closed = True
    except asyncio.TimeoutError:
        old_closed = False
    check("stale connection closed by server", old_closed)
    ws = ws2

    # allow-list
    h.cmd("ALLOW 192.0.2.99")
    try:
        bad = await websockets.connect(URL)
        await asyncio.wait_for(bad.recv(), 2)
        rejected = False
    except Exception:
        rejected = True
    await asyncio.sleep(0.2)
    check("non-allowed IP rejected", rejected and "rejected=1" in h.cmd("STATE"))
    h.cmd(f"ALLOW {IP}")
    ok_ws = await websockets.connect(URL)
    await recv(ok_ws)
    check("allowed TV IP accepted", True)
    await ok_ws.close()
    h.cmd("ALLOW -")
    ws = await websockets.connect(URL)
    await recv(ws)

    # restart on same port (SO_REUSEADDR)
    check("stop", h.cmd("STOP") == "STOPPED")
    check("send when stopped", h.cmd("SEND x") == "SEND NOT_RUNNING 0")
    check("immediate restart on same port", h.cmd(f"START {PORT} -") == "START OK")
    ws = await websockets.connect(URL)
    await recv(ws)

    # port conflict reported
    blocker = socket.socket()
    blocker.bind(("0.0.0.0", 8799))
    blocker.listen()
    r = h.cmd("START 8799 -")
    check("port-in-use error reported clearly", r.startswith("START Port 8799 açılamadı"), r)
    blocker.close()
    h.cmd(f"START {PORT} -")
    ws = await websockets.connect(URL)
    await recv(ws)

    # stability: 90 s, 1 msg / 50 ms with an idle client relying on ping/pong
    t0 = time.time()
    n = 0
    lost = 0
    while time.time() - t0 < 90:
        h.cmd(f"SEND uzun test {n}")
        try:
            m = json.loads(await recv(ws, 2))
            if m["text"] != f"uzun test {n}":
                lost += 1
        except Exception:
            lost += 1
        n += 1
        await asyncio.sleep(0.05)
    check("90 s stability run", lost == 0 and n > 1000, f"{n} messages, {lost} lost")
    st = h.cmd("STATE")
    check("still 1 client after long run", "clients=1" in st, st)

    h.cmd("QUIT")
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


asyncio.run(main())
