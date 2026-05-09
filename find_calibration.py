"""Find calibration window from telemetry, then look for trigger commands."""
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

# Find big motion in cmdSet=0x04/cmdId=0x57 telemetry
print("Big motion events (>30 in any axis):")
prev = None
motion_starts = []
last_motion_t = -10
for ts, d, v in events:
    if d != "RX": continue
    if v[9] != 0x04 or v[10] != 0x57: continue
    pl = v[11:-2]
    if len(pl) != 8: continue
    a1, a2, a3 = struct.unpack("<hhh", pl[:6])
    t = (ts-ts0)/1e6
    if prev is None:
        prev = (a1, a2, a3); continue
    delta = max(abs(c-p) for c, p in zip((a1, a2, a3), prev))
    if delta > 30:
        # Group close events into windows
        if t - last_motion_t > 5:
            print(f"  *** MOTION WINDOW STARTING t={t:.2f}s  ({a1/10:+.1f}, {a2/10:+.1f}, {a3/10:+.1f})")
            motion_starts.append(ts)
        last_motion_t = t
    prev = (a1, a2, a3)

# For each motion window start, find unique TX commands in 5s preceding
# (excluding the spam commands)
SPAM_CMDS = {(0x04, 0x10), (0x04, 0x57), (0x04, 0x68), (0x04, 0x50), (0x04, 0x66),
             (0x04, 0x6F), (0x04, 0x77), (0x04, 0x71), (0x04, 0x05), (0x04, 0x38),
             (0x04, 0x1C), (0xEE, 0x02), (0x00, 0x00), (0x00, 0x01), (0x00, 0x0C),
             (0x03, 0xDA), (0x07, 0x18), (0x04, 0x52), (0x00, 0x4F), (0x00, 0x32),
             (0x04, 0x54), (0x00, 0x4A), (0x00, 0x2B)}

print(f"\n=== {len(motion_starts)} motion windows ===")
for mt in motion_starts:
    t_rel = (mt-ts0)/1e6
    print(f"\n--- Motion window at t={t_rel:.2f}s — preceding 5s TX (non-spam) ---")
    seen_in_window = set()
    for ts, d, v in events:
        if d != "TX": continue
        if ts > mt: break
        if mt - ts > 5_000_000: continue
        cs = v[9]; ci = v[10]
        if (cs, ci) in SPAM_CMDS: continue
        rcv = v[5]; pl = v[11:-2]
        tt = (ts - ts0)/1e6
        key = (cs, ci, pl)
        if key in seen_in_window: continue
        seen_in_window.add(key)
        print(f"  t={tt:.3f}s recv=0x{rcv:02X} set=0x{cs:02X} id=0x{ci:02X} pl={pl.hex().upper()}")
