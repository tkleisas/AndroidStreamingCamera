"""Find big motion in cmdSet=0x04/cmdId=0x57 (8-byte payload) and dump TX nearby."""
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
events = []
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

# Print the cmdSet=0x04 cmdId=0x57 angle telemetry over time
print("Angle changes in cmdSet=0x04/cmdId=0x57 (8B payload, parsed as 3 s16 LE):")
print("  payload[0:2]=A1, [2:4]=A2, [4:6]=A3, [6:8]=flags")
prev = None
big_motion = []
for ts, d, v in events:
    if d != "RX": continue
    if v[9] != 0x04 or v[10] != 0x57: continue
    pl = v[11:-2]
    if len(pl) != 8: continue
    a1, a2, a3 = struct.unpack("<hhh", pl[:6])
    flags = pl[6:8].hex()
    t = (ts-ts0)/1e6
    if prev is None:
        prev = (a1, a2, a3); continue
    if any(abs(c-p) > 30 for c, p in zip((a1, a2, a3), prev)):
        print(f"  t={t:6.2f}s  ({a1/10:+6.1f}, {a2/10:+6.1f}, {a3/10:+6.1f})  flags={flags}")
        big_motion.append(ts)
        prev = (a1, a2, a3)

# For each big motion start, dump ALL TX in the 1s window before
print(f"\n=== {len(big_motion)} big motion events ===")
seen_actions = set()
for mt in big_motion:
    t_rel = (mt-ts0)/1e6
    print(f"\n--- Motion at t={t_rel:.2f}s — TX in preceding 1s ---")
    for ts, d, v in events:
        if d != "TX": continue
        if ts > mt: break
        if mt - ts > 1_000_000: continue
        rcv = v[5]; cs = v[9]; ci = v[10]; pl = v[11:-2]
        # Skip the always-busy ones
        if cs == 0x04 and ci in (0x10, 0x57, 0x68, 0x50, 0x66, 0x6F, 0x77, 0x71, 0x05, 0x38, 0x1C): continue
        if cs == 0xEE: continue
        if cs == 0x00 and ci in (0x00,): continue
        tt = (ts - ts0)/1e6
        print(f"  t={tt:.3f}s recv=0x{rcv:02X} set=0x{cs:02X} id=0x{ci:02X} pl={pl.hex().upper()}")
