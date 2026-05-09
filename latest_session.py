"""Isolate the most recent connection session and analyze ACK ratio."""
import struct
from collections import Counter

with open("our_app.log", "rb") as f:
    data = f.read()

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, fl, drops = struct.unpack(">IIII", data[pos:pos+16])
    ts = struct.unpack(">Q", data[pos+16:pos+24])[0]
    pos += 24
    records.append((fl, ts, data[pos:pos+incl_len])); pos += incl_len

reasm = {}
events = []
for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    conn = hf & 0x0FFF; pb = (hf >> 12) & 0x3
    if conn != 0x0203: continue
    body = pkt[5:5+ln]
    key = (sent, conn)
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att = body[4:]
        if len(att) < l2_len: reasm[key] = (cid, l2_len, att, ts); continue
    elif pb == 1:
        if key not in reasm: continue
        cid, l2_len, prev, t0 = reasm[key]
        att = prev + body
        if len(att) < l2_len: reasm[key] = (cid, l2_len, att, t0); continue
        del reasm[key]
    else: continue
    if cid != 0x0004: continue
    op = att[0]
    if sent and op in (0x52, 0x12):
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

# Find sessions: split when there's a gap > 30s
sessions = []
current = []
last_ts = None
for ev in events:
    if last_ts is not None and (ev[0] - last_ts) / 1e6 > 30:
        if current: sessions.append(current)
        current = []
    current.append(ev)
    last_ts = ev[0]
if current: sessions.append(current)

print(f"Found {len(sessions)} sessions")
for i, sess in enumerate(sessions[-5:]):
    start = sess[0][0]; end = sess[-1][0]
    duration = (end - start) / 1e6
    tx = sum(1 for e in sess if e[1] == "TX")
    rx = sum(1 for e in sess if e[1] == "RX")
    rx_req = sum(1 for e in sess if e[1] == "RX" and (e[2][8] & 0xE0) == 0x40)
    tx_ack = sum(1 for e in sess if e[1] == "TX" and e[2][8] == 0x80)
    sess_idx = len(sessions) - 5 + i
    print(f"\nSession {sess_idx}: duration={duration:.1f}s  TX={tx} RX={rx}")
    print(f"  RX requests (flags & 0xE0 == 0x40): {rx_req}")
    print(f"  TX ACKs (flags == 0x80): {tx_ack}")
    if rx_req > 0:
        print(f"  ACK ratio: {tx_ack/rx_req*100:.1f}%")

# Detailed breakdown of latest session
if sessions:
    sess = sessions[-1]
    print(f"\n=== Latest session details ===")
    rx_req_breakdown = Counter()
    for e in sess:
        if e[1] != "RX": continue
        if (e[2][8] & 0xE0) != 0x40: continue
        rx_req_breakdown[(e[2][4], e[2][9], e[2][10])] += 1
    print("RX requests breakdown (sender, set, id):")
    for (s, cs, ci), cnt in rx_req_breakdown.most_common():
        print(f"  sender=0x{s:02X} set=0x{cs:02X} id=0x{ci:02X}: {cnt}")

    tx_ack_breakdown = Counter()
    for e in sess:
        if e[1] != "TX" or e[2][8] != 0x80: continue
        tx_ack_breakdown[(e[2][5], e[2][9], e[2][10])] += 1
    print("\nTX ACKs breakdown (recv, set, id):")
    for (r, cs, ci), cnt in tx_ack_breakdown.most_common():
        print(f"  recv=0x{r:02X} set=0x{cs:02X} id=0x{ci:02X}: {cnt}")
