"""Real network tests for the Kotlin SubtitleServerCore (runs the compiled Kotlin on a JVM).

Clients connect over the container's non-loopback interface IP to mimic another LAN device.
"""
import asyncio
import json
import os
import socket
import statistics
import subprocess
import sys
import time

import websockets

JARS = "/opt/jars/Java-WebSocket-1.6.0.jar:/opt/jars/slf4j-api-2.0.13.jar"
CP = os.environ.get("PS5_WS_TEST_CLASSPATH", f"/tmp/ktbuild/harness.jar:{JARS}:/opt/kt/kotlinc/lib/kotlin-stdlib.jar")
results = []


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    finally:
        s.close()


IP = lan_ip()
with socket.socket() as probe:
    probe.bind(("0.0.0.0", 0))
    PORT = probe.getsockname()[1]
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
    start = h.cmd(f"START {PORT} -")
    check("server starts on the LAN port", start == "START OK", start)
    assert start == "START OK"
    check("send with no TV", h.cmd("SEND Merhaba") == "SEND NO_CLIENTS 0")

    ws = await websockets.connect(URL, proxy=None)
    replay = await recv(ws)
    check("TV connecting later gets current subtitle replayed", json.loads(replay)["text"] == "Merhaba" and 0 < json.loads(replay)["ttlMs"] <= 2000, replay)
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
    other = await websockets.connect(f"ws://127.0.0.1:{PORT}", proxy=None)
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
    ws2 = await websockets.connect(URL, proxy=None)
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
        bad = await websockets.connect(URL, proxy=None)
        await asyncio.wait_for(bad.recv(), 2)
        rejected = False
    except Exception:
        rejected = True
    await asyncio.sleep(0.2)
    check("non-allowed IP rejected", rejected and "rejected=1" in h.cmd("STATE"))
    h.cmd(f"ALLOW {IP}")
    ok_ws = await websockets.connect(URL, proxy=None)
    await recv(ok_ws)
    check("allowed TV IP accepted", True)
    await ok_ws.close()
    h.cmd("ALLOW -")
    ws = await websockets.connect(URL, proxy=None)
    await recv(ws)

    # restart on same port (SO_REUSEADDR)
    check("stop", h.cmd("STOP") == "STOPPED")
    check("send when stopped", h.cmd("SEND x") == "SEND NOT_RUNNING 0")
    check("immediate restart on same port", h.cmd(f"START {PORT} -") == "START OK")
    ws = await websockets.connect(URL, proxy=None)
    check("server restart has no stale replay", await no_msg(ws))

    # port conflict reported
    blocker = socket.socket()
    blocker.bind(("0.0.0.0", 0))
    blocker.listen()
    busy_port = blocker.getsockname()[1]
    r = h.cmd(f"START {busy_port} -")
    check("port-in-use error reported clearly", r.startswith(f"START Port {busy_port} açılamadı"), r)
    blocker.close()
    h.cmd(f"START {PORT} -")
    ws = await websockets.connect(URL, proxy=None)
    await ws.send('{"type":"hello","subtitleVersion":2}')
    await asyncio.sleep(0.1)
    await ws.send('{"type":"ping"}')
    check("client-initiated heartbeat receives pong", json.loads(await recv(ws)) == {"type": "pong"})
    h.cmd("TTL 800 Zamanlı")
    check("live messages carry the configured TTL", json.loads(await recv(ws)) == {"text": "Zamanlı", "ttlMs": 800})
    await asyncio.sleep(0.4)
    check("still-visible subtitle extends expiry", h.cmd("REFRESH 800 Zamanlı") == "true")
    check("refresh sends no duplicate text", json.loads(await recv(ws)) == {"type": "refresh", "ttlMs": 800})
    check("refresh supersedes the original timer", await no_msg(ws, 0.5))
    check("expiry clears the subtitle", json.loads(await recv(ws)) == {"text": ""})
    await ws.close()
    ws = await websockets.connect(URL, proxy=None)
    check("expired subtitles are not replayed", await no_msg(ws))

    # Overlay protocol is negotiated independently of the existing text stream.
    legacy = await websockets.connect(f"ws://127.0.0.1:{PORT}", proxy=None)
    await legacy.send('{"type":"hello","subtitleVersion":2}')
    await ws.send('{"type":"hello","subtitleVersion":3}')
    await asyncio.sleep(0.12)
    check("only the v3 TV receives the overlay", h.cmd("OVERLAY") == "1" and await no_msg(legacy, 0.1))
    overlay = json.loads(await recv(ws))
    check("separate spatial boxes retain bounds, text and expiry", overlay["type"] == "overlay"
          and (overlay["frameWidth"], overlay["frameHeight"]) == (1280, 720)
          and [(x["id"], x["ttlMs"], x["y"]) for x in overlay["blocks"]] == [("1", 1000, 0.2), ("2", 2500, 0.7)]
          and overlay["blocks"][0]["text"] == 'Görev "başladı"', str(overlay))
    await asyncio.sleep(1.05)
    await ws.close()
    ws = await websockets.connect(URL, proxy=None)
    check("overlay waits for negotiation on reconnect", await no_msg(ws, 0.1))
    await ws.send('{"type":"hello","subtitleVersion":3}')
    replay = json.loads(await recv(ws))
    check("overlay replay excludes expired boxes", len(replay["blocks"]) == 1 and replay["blocks"][0]["id"] == "2"
          and 0 < replay["blocks"][0]["ttlMs"] < 2500, str(replay))
    check("clear overlay is an empty snapshot", h.cmd("CLEAROVERLAY") == "1" and json.loads(await recv(ws))["blocks"] == [])
    h.cmd("TTL 2000 Altyazı geri döndü")
    check("legacy subtitle continues after overlay", json.loads(await recv(ws))["text"] == "Altyazı geri döndü"
          and json.loads(await recv(legacy))["text"] == "Altyazı geri döndü")
    check("v3 TVs still receive subtitle refresh", h.cmd("REFRESH 2000 Altyazı geri döndü") == "true"
          and json.loads(await recv(ws))["type"] == "refresh" and json.loads(await recv(legacy))["type"] == "refresh")
    await legacy.close()

    # stability: 90 s, 1 msg / 50 ms with an idle client relying on ping/pong
    t0 = time.monotonic()
    n = 0
    lost = 0
    timings = []
    while time.monotonic() - t0 < 90:
        status, clients, queued_ns = h.cmd(f"TIMED uzun test {n}").split()
        try:
            m = json.loads(await recv(ws, 2))
            if m["text"] != f"uzun test {n}" or (status, clients) != ("SENT", "1"):
                lost += 1
            else:
                timings.append(int(queued_ns) / 1e6)
        except Exception:
            lost += 1
        n += 1
        await asyncio.sleep(0.05)
    check("90 s stability run", lost == 0 and n > 1000, f"{n} messages, {lost} lost")
    if len(timings) >= 2:
        print(f"Host WebSocket enqueue p50={statistics.median(timings):.3f} ms "
              f"p95={statistics.quantiles(timings, n=100)[94]:.3f} ms")
    st = h.cmd("STATE")
    check("still 1 client after long run", "clients=1" in st, st)

    h.cmd("QUIT")
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


asyncio.run(main())
