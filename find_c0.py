"""Find TX frames with flags=0xC0 to understand where they come from."""
import struct

with open("our_app.log", "rb") as f:
    data = f.read()

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, fl, drops = struct.unpack(">IIII", data[pos:pos+16])
    ts = struct.unpack(">Q", data[pos+16:pos+24])[0]
    pos += 24
    records.append((fl, ts, data[pos:pos+incl_len])); pos += incl_len

reasm = {}
ts0 = None
count_c0 = 0
samples_c0 = []
for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if not sent or len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    conn = hf & 0x0FFF; pb = (hf >> 12) & 0x3
    if conn != 0x0203: continue
    body = pkt[5:5+ln]
    key = conn
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
    if cid != 0x0004 or att[0] != 0x52: continue
    h = struct.unpack("<H", att[1:3])[0]
    if h != 0x002b: continue
    v = att[3:]
    if len(v) < 13 or v[0] != 0x55: continue
    if ts0 is None: ts0 = ts
    if v[8] == 0xC0 and v[9] == 0x00 and v[10] == 0x00:
        count_c0 += 1
        if len(samples_c0) < 5:
            samples_c0.append(((ts-ts0)/1e6, v.hex().upper()))

print(f"Total flags=0xC0 cmd=0x00/0x00 frames: {count_c0}")
print("\nSamples:")
for t, hx in samples_c0:
    print(f"  t={t:.2f}s {hx}")
