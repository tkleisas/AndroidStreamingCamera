"""Correlate gimbal angle telemetry changes with preceding TX commands."""
import struct
from collections import defaultdict

with open("btsnoop_hci.log", "rb") as f:
    data = f.read()

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, fl, drops = struct.unpack(">IIII", data[pos:pos+16])
    ts = struct.unpack(">Q", data[pos+16:pos+24])[0]
    pos += 24
    records.append((fl, ts, data[pos:pos+incl_len]))
    pos += incl_len

events = []  # (ts, dir, type, info)
reasm_tx = {}; reasm_rx = {}

for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    handle = hf & 0x0FFF; pb = (hf >> 12) & 0x3
    body = pkt[5:5+ln]
    R = reasm_tx if sent else reasm_rx
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att = body[4:]
        if len(att) < l2_len:
            R[handle] = (cid, l2_len, att); continue
    elif pb == 1:
        if handle not in R: continue
        cid, l2_len, prev = R[handle]
        att = prev + body
        if len(att) < l2_len:
            R[handle] = (cid, l2_len, att); continue
        del R[handle]
    else: continue
    if cid != 0x0004: continue
    op = att[0]
    if op in (0x52, 0x12) and sent:
        h = struct.unpack("<H", att[1:3])[0]
        v = att[3:]
        if h == 0x002b and len(v) >= 13 and v[0] == 0x55:
            events.append((ts, "TX", v))
    elif op == 0x1B and not sent:
        h = struct.unpack("<H", att[1:3])[0]
        v = att[3:]
        if h == 0x0028 and len(v) >= 13 and v[0] == 0x55:
            events.append((ts, "RX", v))

events.sort()
ts0 = events[0][0]

# Find RX telemetry frames containing gimbal angles. Look for cmdSet=0x05 cmdId=0x06
# (53 byte packets in our earlier observation: long telemetry pushes from 0x27)
def parse_angles(v):
    if len(v) < 13: return None
    cs = v[9]; ci = v[10]
    pl = v[11:-2]
    # Format observed: 53 byte frame, payload contains 3 s16 angles probably
    if cs == 0x05 and ci == 0x06 and len(pl) >= 8:
        # pl[2:4], pl[4:6], pl[6:8] perhaps
        a1 = struct.unpack("<h", pl[2:4])[0]
        a2 = struct.unpack("<h", pl[4:6])[0]
        a3 = struct.unpack("<h", pl[6:8])[0]
        # Also try pl[14:16], pl[16:18], pl[18:20] (D10F D10F seen at byte 14 in 0xC30B before)
        return (a1, a2, a3)
    return None

# Find moments when angles changed significantly
prev_angles = None
print("Angle changes in telemetry (cmdSet=0x05 cmdId=0x06 payloads):")
print(f"{'t(s)':>6}  angles                 delta")
movement_times = []
for ts, dir_, v in events:
    if dir_ != "RX": continue
    a = parse_angles(v)
    if a is None: continue
    if prev_angles is not None:
        d = tuple(a[i] - prev_angles[i] for i in range(3))
        if any(abs(x) > 50 for x in d):
            t = (ts - ts0) / 1e6
            print(f"{t:6.2f}  {a}  delta={d}")
            movement_times.append(ts)
    prev_angles = a

# For each movement time, show TX commands in the 1s preceding
print("\n--- TX commands preceding each movement ---")
for mt in movement_times[:10]:
    print(f"\n*** Movement at t={(mt-ts0)/1e6:.2f}s ***")
    for ts, dir_, v in events:
        if dir_ != "TX": continue
        if ts > mt: break
        if mt - ts > 800_000: continue  # within 800ms before
        rcv = v[5]; cs = v[9]; ci = v[10]; pl = v[11:-2]
        t_rel = (ts - ts0)/1e6
        print(f"  t={t_rel:.3f}s recv=0x{rcv:02X} set=0x{cs:02X} id=0x{ci:02X} pl={pl.hex().upper()}")
