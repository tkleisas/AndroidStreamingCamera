"""Look for motion-like commands with timestamps."""
import re
import struct
from collections import Counter, defaultdict

# We need timestamps. Re-parse btsnoop_hci.log directly.
with open("btsnoop_hci.log", "rb") as f:
    data = f.read()
assert data[:8] == b"btsnoop\x00"

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, flags, drops = struct.unpack(">IIII", data[pos:pos+16])
    ts = struct.unpack(">Q", data[pos+16:pos+24])[0]
    pos += 24
    pkt = data[pos:pos+incl_len]
    pos += incl_len
    records.append((flags, ts, pkt))

# Reassemble L2CAP, look for ATT WriteCmd to handle 0x002b
reassembly = {}
duml_writes = []  # (ts, hex_str)
ts_offset = None

for flags, ts, pkt in records:
    sent = (flags & 0x01) == 0
    if not sent: continue
    if len(pkt) < 5 or pkt[0] != 0x02: continue
    handle_flags, length = struct.unpack("<HH", pkt[1:5])
    handle = handle_flags & 0x0FFF
    pb = (handle_flags >> 12) & 0x3
    body = pkt[5:5+length]
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att_data = body[4:]
        if len(att_data) < l2_len:
            reassembly[handle] = (cid, l2_len, att_data, ts)
            continue
    elif pb == 1:
        if handle not in reassembly: continue
        cid, l2_len, prev, ts0 = reassembly[handle]
        att_data = prev + body
        if len(att_data) < l2_len:
            reassembly[handle] = (cid, l2_len, att_data, ts0)
            continue
        del reassembly[handle]
    else:
        continue
    if cid != 0x0004: continue
    if len(att_data) < 3: continue
    op = att_data[0]
    if op != 0x52: continue
    att_handle = struct.unpack("<H", att_data[1:3])[0]
    if att_handle != 0x002b: continue
    val = att_data[3:]
    if len(val) < 13 or val[0] != 0x55: continue

    if ts_offset is None: ts_offset = ts
    rel_ms = (ts - ts_offset) // 1000

    sender = val[4]; receiver = val[5]
    seq = val[6] | (val[7] << 8)
    cmd_set = val[9]; cmd_id = val[10]
    payload = val[11:-2]
    duml_writes.append({
        "t_ms": rel_ms, "len": len(val), "recv": receiver,
        "cmd_set": cmd_set, "cmd_id": cmd_id, "payload": payload,
        "full": val.hex().upper(),
    })

print(f"Total: {len(duml_writes)}")
print(f"Time span: {duml_writes[-1]['t_ms']/1000:.1f}s")
print()

# Find quiet periods (gaps), then bursts
gaps = []
prev_t = 0
for i, w in enumerate(duml_writes):
    if w["t_ms"] - prev_t > 500:
        gaps.append(i)
    prev_t = w["t_ms"]

# Print frames around each gap to show what happens at user-action moments
print("Action candidates (frames after >500ms quiet periods):")
for idx in gaps[:30]:
    if idx == 0: continue
    w = duml_writes[idx]
    prev = duml_writes[idx-1]
    print(f"  t={w['t_ms']/1000:.2f}s gap={(w['t_ms']-prev['t_ms'])/1000:.2f}s recv=0x{w['recv']:02X} set=0x{w['cmd_set']:02X} id=0x{w['cmd_id']:02X} pl={w['payload'].hex().upper()}")

print()
# Tally by (recv, set, id) but also group by similar shapes
# Look for cmd_set/cmd_id pairs that have *long* payloads with varying byte patterns
print("Long-payload commands (>=8B payload, varying):")
groups = defaultdict(list)
for w in duml_writes:
    if len(w["payload"]) >= 8:
        groups[(w["recv"], w["cmd_set"], w["cmd_id"])].append(w)
for k, frames in sorted(groups.items(), key=lambda x: -len(x[1])):
    payloads = set(f["payload"] for f in frames)
    if len(payloads) >= 2:
        r, cs, ci = k
        print(f"\nrecv=0x{r:02X} set=0x{cs:02X} id=0x{ci:02X} count={len(frames)} unique={len(payloads)}")
        for f in frames[:6]:
            print(f"  t={f['t_ms']/1000:.2f}s pl={f['payload'].hex().upper()}")
