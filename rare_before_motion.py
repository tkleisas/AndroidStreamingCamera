"""Find rare-payload commands preceding motion windows."""
import struct
from collections import Counter

with open("btsnoop_hci.log", "rb") as f:
    data = f.read()

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, fl, drops = struct.unpack(">IIII", data[pos:pos+16])
    ts = struct.unpack(">Q", data[pos+16:pos+24])[0]
    pos += 24
    records.append((fl, ts, data[pos:pos+incl_len])); pos += incl_len

reasm_tx, reasm_rx = {}, {}
events = []
for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    conn = hf & 0x0FFF; pb = (hf >> 12) & 0x3
    body = pkt[5:5+ln]
    R = reasm_tx if sent else reasm_rx
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att = body[4:]
        if len(att) < l2_len: R[conn] = (cid, l2_len, att, ts); continue
    elif pb == 1:
        if conn not in R: continue
        cid, l2_len, prev, t0 = R[conn]
        att = prev + body
        if len(att) < l2_len: R[conn] = (cid, l2_len, att, t0); continue
        del R[conn]
    else: continue
    if cid != 0x0004: continue
    op = att[0]
    if sent and op == 0x52:
        h = struct.unpack("<H", att[1:3])[0]
        v = att[3:]
        if h == 0x002b and len(v) >= 13 and v[0] == 0x55:
            events.append((ts, "TX", v))
    elif not sent and op == 0x1B:
        h = struct.unpack("<H", att[1:3])[0]
        v = att[3:]
        if h == 0x0028 and len(v) >= 13 and v[0] == 0x55:
            events.append((ts, "RX", v))

events.sort(key=lambda x: x[0])
ts0 = events[0][0]

# Tally TX by full (cmd_set, cmd_id, payload)
tx_payload_counts = Counter()
for ts, d, v in events:
    if d != "TX": continue
    cs = v[9]; ci = v[10]; pl = v[11:-2]
    tx_payload_counts[(cs, ci, pl)] += 1

# Find motion windows
print("Motion windows + RARE preceding commands (count <=5 same payload):")
prev = None
last_t = -10
for ts, d, v in events:
    if d != "RX": continue
    if v[9] != 0x04 or v[10] != 0x57: continue
    pl = v[11:-2]
    if len(pl) != 8: continue
    a = struct.unpack("<hhh", pl[:6])
    t = (ts-ts0)/1e6
    if prev is None: prev = a; continue
    delta = max(abs(c-p) for c, p in zip(a, prev))
    if delta > 30 and t - last_t > 5:
        print(f"\n=== MOTION at t={t:.2f}s pos=({a[0]/10:+.1f}, {a[1]/10:+.1f}, {a[2]/10:+.1f}) ===")
        # Find rare TX commands in preceding 8s
        seen = set()
        for ts2, d2, v2 in events:
            if d2 != "TX" or ts2 > ts: continue
            if ts - ts2 > 8_000_000: continue
            cs2 = v2[9]; ci2 = v2[10]; pl2 = v2[11:-2]
            count = tx_payload_counts[(cs2, ci2, pl2)]
            if count > 5: continue
            key = (cs2, ci2, pl2)
            if key in seen: continue
            seen.add(key)
            tt = (ts2-ts0)/1e6
            rcv = v2[5]
            print(f"   t={tt:7.3f}s [{count:3d}x] recv=0x{rcv:02X} set=0x{cs2:02X} id=0x{ci2:02X} pl={pl2.hex().upper()}")
        last_t = t
    prev = a
