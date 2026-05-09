"""Dump TX writes to all NON-0x002b handles, with timestamps."""
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

reasm = {}
writes = []
ts0 = None
for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if not sent or len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    handle = hf & 0x0FFF; pb = (hf >> 12) & 0x3
    body = pkt[5:5+ln]
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att = body[4:]
        if len(att) < l2_len: reasm[handle] = (cid, l2_len, att, ts); continue
    elif pb == 1:
        if handle not in reasm: continue
        cid, l2_len, prev, t0 = reasm[handle]
        att = prev + body
        if len(att) < l2_len: reasm[handle] = (cid, l2_len, att, t0); continue
        del reasm[handle]
    else: continue
    if cid != 0x0004: continue
    op = att[0]
    if op not in (0x52, 0x12): continue
    h = struct.unpack("<H", att[1:3])[0]
    val = att[3:]
    if ts0 is None: ts0 = ts
    writes.append(((ts-ts0)/1e6, h, val))

# Print writes to non-0x002b handles
print("Writes to non-FFF5 handles:")
for t, h, v in writes:
    if h == 0x002b: continue
    if h == 0x0029: continue  # CCCD
    print(f"  t={t:6.2f}s handle=0x{h:04x} ({len(v):2d}B) {v.hex().upper()}")
