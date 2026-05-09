"""Find the command that starts ActiveTrack."""
import struct
from collections import Counter

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
    writes.append({
        "t": (ts-ts0)/1e6, "recv": val[5], "set": val[9], "id": val[10],
        "pl": val[11:-2],
    })

# Find time of first 0x23/0x09
first_track = next(w for w in writes if w["set"] == 0x23 and w["id"] == 0x09)
print(f"First ActiveTrack frame: t={first_track['t']:.2f}s")

# Tally payload counts
counts = Counter((w["set"], w["id"], w["pl"]) for w in writes)

# Print all writes in 10s window before first 0x23/0x09 (excluding spam)
SPAM = {(0x04, 0x10), (0x04, 0x57), (0x04, 0x68), (0x04, 0x50), (0x04, 0x66),
        (0x04, 0x6F), (0x04, 0x77), (0x04, 0x71), (0x04, 0x05), (0x04, 0x38),
        (0x04, 0x1C), (0xEE, 0x02), (0x00, 0x00), (0x00, 0x01), (0x00, 0x0C),
        (0x03, 0xDA), (0x07, 0x18), (0x04, 0x52), (0x00, 0x4F),
        (0x00, 0x32), (0x04, 0x54), (0x00, 0x4A), (0x00, 0x2B)}

print(f"\n=== TX writes 10s before first ActiveTrack frame ({first_track['t']:.2f}s) ===")
for w in writes:
    if w["t"] >= first_track["t"]: break
    if first_track["t"] - w["t"] > 10: continue
    if (w["set"], w["id"]) in SPAM: continue
    cnt = counts[(w["set"], w["id"], w["pl"])]
    print(f"  t={w['t']:7.2f}s [{cnt:4d}x] recv=0x{w['recv']:02X} set=0x{w['set']:02X} id=0x{w['id']:02X} pl={w['pl'].hex().upper()}")

# Find all unique 0x04/0x4C and 0x04/0x07 writes with timestamps
print("\n=== set=0x04 id=0x4C all writes (likely tracking on/off) ===")
for w in writes:
    if w["set"] == 0x04 and w["id"] == 0x4C:
        print(f"  t={w['t']:7.2f}s pl={w['pl'].hex().upper()}")

print("\n=== set=0x04 id=0x07 all writes ===")
for w in writes:
    if w["set"] == 0x04 and w["id"] == 0x07:
        print(f"  t={w['t']:7.2f}s pl={w['pl'].hex().upper()}")
