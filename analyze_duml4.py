"""Print every unique (cmd_set, cmd_id) combination Mimo uses, with payload examples."""
import re
import struct
from collections import defaultdict

with open("btsnoop_hci.log", "rb") as f:
    data = f.read()

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, flags, drops = struct.unpack(">IIII", data[pos:pos+16])
    ts = struct.unpack(">Q", data[pos+16:pos+24])[0]
    pos += 24
    pkt = data[pos:pos+incl_len]
    pos += incl_len
    records.append((flags, ts, pkt))

reassembly = {}
duml = []
ts0 = None
for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if not sent: continue
    if len(pkt) < 5 or pkt[0] != 0x02: continue
    handle_flags, length = struct.unpack("<HH", pkt[1:5])
    handle = handle_flags & 0x0FFF
    pb = (handle_flags >> 12) & 0x3
    body = pkt[5:5+length]
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att = body[4:]
        if len(att) < l2_len:
            reassembly[handle] = (cid, l2_len, att, ts); continue
    elif pb == 1:
        if handle not in reassembly: continue
        cid, l2_len, prev, ts_first = reassembly[handle]
        att = prev + body
        if len(att) < l2_len:
            reassembly[handle] = (cid, l2_len, att, ts_first); continue
        del reassembly[handle]
    else: continue
    if cid != 0x0004: continue
    if len(att) < 3 or att[0] != 0x52: continue
    h = struct.unpack("<H", att[1:3])[0]
    if h != 0x002b: continue
    val = att[3:]
    if len(val) < 13 or val[0] != 0x55: continue
    if ts0 is None: ts0 = ts
    duml.append({
        "t": (ts - ts0) / 1e6,
        "recv": val[5], "set": val[9], "id": val[10],
        "pl": val[11:-2], "full": val.hex().upper(),
    })

print(f"Total: {len(duml)}, span: {duml[-1]['t']:.1f}s")
print()

groups = defaultdict(list)
for f in duml:
    groups[(f["set"], f["id"])].append(f)

for (cs, ci), frames in sorted(groups.items()):
    times = [f["t"] for f in frames]
    pl_lens = set(len(f["pl"]) for f in frames)
    receivers = set(f["recv"] for f in frames)
    unique_pl = set(f["pl"] for f in frames)
    print(f"set=0x{cs:02X} id=0x{ci:02X}  count={len(frames)} recv={[hex(r) for r in receivers]} pl_lens={pl_lens} unique_pl={len(unique_pl)}")
    if len(unique_pl) <= 8:
        for pl in unique_pl:
            print(f"   pl={pl.hex().upper()}")
