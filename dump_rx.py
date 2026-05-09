"""Print RX telemetry frames over time to see angle byte changes."""
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
    records.append((fl, ts, data[pos:pos+incl_len])); pos += incl_len

reasm = {}
events = []
for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if sent or len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    handle = hf & 0x0FFF; pb = (hf >> 12) & 0x3
    body = pkt[5:5+ln]
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att = body[4:]
        if len(att) < l2_len: reasm[handle] = (cid, l2_len, att); continue
    elif pb == 1:
        if handle not in reasm: continue
        cid, l2_len, prev = reasm[handle]
        att = prev + body
        if len(att) < l2_len: reasm[handle] = (cid, l2_len, att); continue
        del reasm[handle]
    else: continue
    if cid != 0x0004 or att[0] != 0x1B: continue
    h = struct.unpack("<H", att[1:3])[0]
    v = att[3:]
    if h != 0x0028 or len(v) < 13 or v[0] != 0x55: continue
    events.append((ts, v))

ts0 = events[0][0]

# Group RX by (cmdSet, cmdId)
groups = defaultdict(list)
for ts, v in events:
    groups[(v[9], v[10])].append((ts, v))

print("RX frames by cmdSet/cmdId:")
for (cs, ci), frames in sorted(groups.items()):
    pl_lens = set(len(f[1])-13 for f in frames)
    print(f"  set=0x{cs:02X} id=0x{ci:02X} count={len(frames)} pl_lens={pl_lens}")

# For the most-frequent telemetry frame, sample over time to see byte changes
target = max(groups.items(), key=lambda x: len(x[1]))
print(f"\nSampling top: set=0x{target[0][0]:02X} id=0x{target[0][1]:02X} ({len(target[1])} frames)")
sampled = target[1][::max(1, len(target[1])//30)]
for ts, v in sampled:
    pl = v[11:-2]
    print(f"  t={(ts-ts0)/1e6:6.2f}s pl={pl.hex().upper()}")
