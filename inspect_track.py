"""Inspect cmdSet=0x23/cmdId=0x09 payloads (likely ActiveTrack stream)."""
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
ts0 = None
all_writes = []
for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if not sent or len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    conn = hf & 0x0FFF; pb = (hf >> 12) & 0x3
    body = pkt[5:5+ln]
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att = body[4:]
        if len(att) < l2_len: reasm[conn] = (cid, l2_len, att, ts); continue
    elif pb == 1:
        if conn not in reasm: continue
        cid, l2_len, prev, t0 = reasm[conn]
        att = prev + body
        if len(att) < l2_len: reasm[conn] = (cid, l2_len, att, t0); continue
        del reasm[conn]
    else: continue
    if cid != 0x0004 or att[0] != 0x52: continue
    h = struct.unpack("<H", att[1:3])[0]
    if h != 0x002b: continue
    val = att[3:]
    if len(val) < 13 or val[0] != 0x55: continue
    if ts0 is None: ts0 = ts
    all_writes.append({"t": (ts-ts0)/1e6, "v": val})

# Find 0x23/0x09 frames
track = [w for w in all_writes if w["v"][9] == 0x23 and w["v"][10] == 0x09]
print(f"Total 0x23/0x09 frames: {len(track)}")
print(f"Time range: {track[0]['t']:.1f}s to {track[-1]['t']:.1f}s")

# Check time density
print("\nTime density (60s buckets):")
buckets = {}
for w in track:
    b = int(w["t"]) // 60
    buckets[b] = buckets.get(b, 0) + 1
for b in sorted(buckets.keys()):
    if buckets[b] > 0:
        bar = "#" * min(80, buckets[b]//5)
        print(f"  {b*60:5}s: {buckets[b]:5d} {bar}")

print("\nFirst 3 frames - 68 bytes parsed as 17 floats AND as raw hex:")
for i, w in enumerate(track[:3]):
    pl = w["v"][11:-2]
    print(f"\n[{i}] t={w['t']:.2f}s pl_hex={pl.hex().upper()}")
    floats = struct.unpack("<17f", pl)
    print(f"   floats={[f'{f:+.4f}' for f in floats]}")

# Look at how floats vary across consecutive frames during a tracking burst
print("\nVariation in floats over 10 consecutive frames (mid-stream):")
mid = len(track)//2
for w in track[mid:mid+10]:
    pl = w["v"][11:-2]
    floats = struct.unpack("<17f", pl)
    print(f"  t={w['t']:7.2f}s {' '.join(f'{f:+8.3f}' for f in floats)}")
