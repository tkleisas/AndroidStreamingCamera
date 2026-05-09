"""Find the most recent Mimo session and identify the calibration trigger."""
import struct
from collections import Counter, defaultdict

with open("btsnoop_hci.log", "rb") as f:
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

# Find sessions: gap > 60s between events
sessions = []
current = []
last_ts = None
for ev in events:
    if last_ts is not None and (ev[0] - last_ts) / 1e6 > 60:
        if current: sessions.append(current)
        current = []
    current.append(ev)
    last_ts = ev[0]
if current: sessions.append(current)

# Show session summary
print(f"Sessions: {len(sessions)}")
for i, sess in enumerate(sessions):
    if len(sess) < 50: continue
    duration = (sess[-1][0] - sess[0][0]) / 1e6
    tx = sum(1 for e in sess if e[1] == "TX")
    rx = sum(1 for e in sess if e[1] == "RX")
    # Identify "Mimo" sessions: high TX/RX with cmd 0x23/0x09 or unique cmds we don't send
    has_track = any(e[1] == "TX" and e[2][9] == 0x23 and e[2][10] == 0x09 for e in sess)
    has_imu = any(e[1] == "TX" and e[2][9] == 0x04 and e[2][10] == 0x52 for e in sess)
    print(f"  S{i}: {duration:.0f}s TX={tx} RX={rx} mimo_track={has_track} imu={has_imu}")

# Pick last Mimo session
mimo_sessions = []
for i, sess in enumerate(sessions):
    has_imu = any(e[1] == "TX" and e[2][9] == 0x04 and e[2][10] == 0x52 for e in sess)
    has_long_payloads = any(e[1] == "TX" and len(e[2]) > 50 for e in sess)
    if has_imu or has_long_payloads:
        mimo_sessions.append((i, sess))
if not mimo_sessions:
    print("No Mimo session found (no IMU stream / long payloads)")
    exit()
last_idx, last_mimo = mimo_sessions[-1]
ts0 = last_mimo[0][0]
duration = (last_mimo[-1][0] - ts0) / 1e6
print(f"\n=== Last Mimo session: index {last_idx}, duration {duration:.0f}s ===")

# Find motion windows from set=0x04/id=0x57 telemetry
print("\nLooking for big motion (calibration sweeps):")
prev = None
last_motion_t = -100
motion_starts = []
for ts, d, v in last_mimo:
    if d != "RX": continue
    if v[9] != 0x04 or v[10] != 0x57: continue
    pl = v[11:-2]
    if len(pl) != 8: continue
    a = struct.unpack("<hhh", pl[:6])
    t = (ts-ts0)/1e6
    if prev is None:
        prev = a; continue
    delta = max(abs(c-p) for c, p in zip(a, prev))
    if delta > 100 and t - last_motion_t > 5:
        print(f"  *** MOTION at t={t:.2f}s pos=({a[0]/10:+.1f}, {a[1]/10:+.1f}, {a[2]/10:+.1f})")
        motion_starts.append(ts)
        last_motion_t = t
    prev = a

# For each motion start, look at unique TX commands in the 60s preceding
# (calibration trigger may be sent up to ~60s before sweep begins per Mimo's UI flow)
SPAM_CMDS = {
    (0x04, 0x10), (0x04, 0x57), (0x04, 0x68), (0x04, 0x50), (0x04, 0x66),
    (0x04, 0x6F), (0x04, 0x77), (0x04, 0x71), (0x04, 0x05), (0x04, 0x38),
    (0x04, 0x1C), (0xEE, 0x02), (0x00, 0x00), (0x00, 0x01), (0x00, 0x0C),
    (0x03, 0xDA), (0x07, 0x18), (0x04, 0x52), (0x00, 0x4F),
    (0x00, 0x32), (0x04, 0x54), (0x00, 0x4A), (0x00, 0x2B),
    (0x23, 0x09),
}

# Tally payload counts within session
counts = Counter()
for ts, d, v in last_mimo:
    if d != "TX": continue
    counts[(v[9], v[10], v[11:-2])] += 1

print(f"\n=== Unique-payload commands during last Mimo session (count <= 5) ===")
seen_keys = set()
last_key_time = {}
for ts, d, v in last_mimo:
    if d != "TX": continue
    cs = v[9]; ci = v[10]; pl = v[11:-2]
    if (cs, ci) in SPAM_CMDS: continue
    cnt = counts[(cs, ci, pl)]
    if cnt > 5: continue
    key = (cs, ci, pl)
    last_seen = last_key_time.get(key, -1000)
    t = (ts-ts0)/1e6
    if t - last_seen < 0.2:
        last_key_time[key] = t
        continue
    last_key_time[key] = t
    rcv = v[5]
    print(f"  t={t:7.2f}s [{cnt}x] recv=0x{rcv:02X} set=0x{cs:02X} id=0x{ci:02X} pl={pl.hex().upper()}")
