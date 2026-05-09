"""Diagnose our app's BLE traffic - find the moment things broke and what was/wasn't sent."""
import struct
from collections import defaultdict, Counter

LOG = "our_app.log"

with open(LOG, "rb") as f:
    data = f.read()

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, fl, drops = struct.unpack(">IIII", data[pos:pos+16])
    ts = struct.unpack(">Q", data[pos+16:pos+24])[0]
    pos += 24
    records.append((fl, ts, data[pos:pos+incl_len])); pos += incl_len

# Reassemble per (sent, conn)
reasm = {}
events = []  # (ts, dir, conn, frame)
for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    conn = hf & 0x0FFF; pb = (hf >> 12) & 0x3
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
            events.append((ts, "TX", conn, v))
    elif not sent and op == 0x1B:
        h = struct.unpack("<H", att[1:3])[0]
        v = att[3:]
        if h == 0x0028 and len(v) >= 13 and v[0] == 0x55:
            events.append((ts, "RX", conn, v))

if not events:
    print("No DUML events found")
    exit()

events.sort(key=lambda x: x[0])
ts0 = events[0][0]
total_span = (events[-1][0] - ts0) / 1e6
print(f"Total events: {len(events)} over {total_span:.1f}s")

# Identify connections that had OM7 traffic (large notify volume)
conn_rx_count = Counter()
for ts, d, c, v in events:
    if d == "RX": conn_rx_count[c] += 1
print(f"\nConnections by RX notify count: {dict(conn_rx_count.most_common())}")
gimbal_conn = conn_rx_count.most_common(1)[0][0]
print(f"Gimbal connection: 0x{gimbal_conn:04x}")

# Filter to gimbal events only
gimbal_events = [e for e in events if e[2] == gimbal_conn]
print(f"Gimbal events: {len(gimbal_events)}")

# TX/RX rate per 10s bucket
print("\nTX/RX activity per 10s bucket:")
buckets_tx = Counter()
buckets_rx = Counter()
for ts, d, c, v in gimbal_events:
    b = int((ts - ts0) / 1e6) // 10
    if d == "TX": buckets_tx[b] += 1
    else: buckets_rx[b] += 1
for b in range(0, int(total_span // 10) + 2):
    if buckets_tx[b] or buckets_rx[b]:
        print(f"  {b*10:4}s..{b*10+10:4}s  TX={buckets_tx[b]:5d}  RX={buckets_rx[b]:5d}")

# What ARE we sending?
print("\nTX commands sent by our app (top 15 by count):")
tx_cmds = Counter()
for ts, d, c, v in gimbal_events:
    if d != "TX": continue
    rcv = v[5]; flags = v[8]; cs = v[9]; ci = v[10]
    tx_cmds[(rcv, flags, cs, ci)] += 1
for (rcv, fl, cs, ci), cnt in tx_cmds.most_common(15):
    print(f"  recv=0x{rcv:02X} flags=0x{fl:02X} set=0x{cs:02X} id=0x{ci:02X}: {cnt}x")

# Check if our ACKs are going out (should be flags=0x80)
ack_count = sum(1 for ts, d, c, v in gimbal_events if d == "TX" and v[8] == 0x80)
print(f"\nTotal ACKs (flags=0x80) we sent: {ack_count}")

# Check what gimbal sends with flags=0x40 (requests expecting ack)
req_count = sum(1 for ts, d, c, v in gimbal_events if d == "RX" and (v[8] & 0xE0) == 0x40)
print(f"Total RX requests (flags & 0xE0 == 0x40) gimbal sent: {req_count}")
print(f"Ratio acked: {ack_count}/{req_count} = {ack_count/max(1,req_count)*100:.1f}%")

# Find any gap in RX (>5s) that could indicate gimbal stopped responding
print("\nGaps in RX > 5s (possible motor lockout):")
last_rx_ts = None
for ts, d, c, v in gimbal_events:
    if d != "RX": continue
    if last_rx_ts is not None:
        gap = (ts - last_rx_ts) / 1e6
        if gap > 5.0:
            t = (ts - ts0)/1e6
            print(f"  At t={t:.1f}s: gap of {gap:.2f}s before this RX")
    last_rx_ts = ts
