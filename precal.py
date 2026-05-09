"""Find DUML writes to gimbal in the t=12-23s window (preceding calibration motion)."""
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
    conn = hf & 0x0FFF; pb = (hf >> 12) & 0x3
    body = pkt[5:5+ln]
    if conn != 0x0203: continue  # gimbal-only
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
        "pl": val[11:-2], "full": val.hex().upper(),
    })

# Filter by time window 12-23s
print("DUML writes in t=12-23s window (gimbal connection):")
for w in writes:
    if 12.0 <= w["t"] <= 23.0:
        print(f"  t={w['t']:6.2f}s recv=0x{w['recv']:02X} set=0x{w['set']:02X} id=0x{w['id']:02X} pl={w['pl'].hex().upper()}")
