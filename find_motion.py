"""Find motion windows from set=0x05/id=0x06 telemetry, then dump TX in those windows."""
import struct

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
events = []  # (ts, dir, frame)
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
        if len(att) < l2_len: R[handle] = (cid, l2_len, att); continue
    elif pb == 1:
        if handle not in R: continue
        cid, l2_len, prev = R[handle]
        att = prev + body
        if len(att) < l2_len: R[handle] = (cid, l2_len, att); continue
        del R[handle]
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
ts0 = events[0][0]

# Look at set=0x05 id=0x06 telemetry. It has 40-byte payload.
# From earlier: 5535046827021501400506 55 D00F00000000000000000000000000 D10F D10F 000000000000000000000000 C30B 000000000000 4D8C
# Payload (after 0x55 0x06 cmdSet/cmdId at offsets 9-10): bytes 11..len-3.
# For 53-byte frame (0x35 in first byte after 0x55): payload is at bytes 11..50, 40 bytes.
# In payload: looks like at offset ~14-19 are 3 angles as s16 little-endian.
print("Angle telemetry over time (from cmdSet=0x05 cmdId=0x06):")
prev_ang = None
motion_starts = []
for ts, d, v in events:
    if d != "RX": continue
    if v[9] != 0x05 or v[10] != 0x06: continue
    pl = v[11:-2]
    if len(pl) < 20: continue
    # Try angles at various offsets
    a14 = struct.unpack("<hhh", pl[14:20])
    t = (ts-ts0)/1e6
    if prev_ang is None or any(abs(a14[i]-prev_ang[i]) > 30 for i in range(3)):
        deg = tuple(a/10.0 for a in a14)
        print(f"  t={t:6.2f}s pitch={deg[0]:+6.1f} roll={deg[1]:+6.1f} yaw={deg[2]:+6.1f}  raw={a14}")
        if prev_ang is not None and any(abs(a14[i]-prev_ang[i]) > 30 for i in range(3)):
            motion_starts.append(ts)
    prev_ang = a14

# For each significant motion, dump TX commands in 600ms preceding
print(f"\n=== Motion events found: {len(motion_starts)} ===")
for mt in motion_starts:
    t_rel = (mt-ts0)/1e6
    print(f"\n--- Motion at t={t_rel:.2f}s — preceding 600ms TX ---")
    for ts, d, v in events:
        if d != "TX": continue
        if ts > mt: break
        if mt - ts > 600_000: continue
        rcv = v[5]; cs = v[9]; ci = v[10]; pl = v[11:-2]
        if cs == 0x04 and ci in (0x10, 0x57, 0x68, 0x50, 0x66, 0x6F, 0x77, 0x71, 0x05, 0x38, 0x1C):
            continue  # Skip status pollers
        if cs == 0xEE: continue  # skip the 803F float spam
        if cs == 0x00 and ci in (0x00, 0x01, 0x0C): continue  # skip heartbeats
        if cs == 0x03: continue
        if cs == 0x07: continue
        tt = (ts - ts0)/1e6
        print(f"  t={tt:.3f}s recv=0x{rcv:02X} set=0x{cs:02X} id=0x{ci:02X} pl={pl.hex().upper()}")
