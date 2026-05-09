"""Group writes by ACL connection handle to identify which connection is the gimbal."""
import struct
from collections import defaultdict, Counter

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
ts0 = None
# {conn_handle: {att_handle_dir: count}}
per_conn = defaultdict(Counter)
# {(conn_handle, att_handle, dir): list of (ts_rel, value_hex)}
per_conn_writes = defaultdict(list)

for fl, ts, pkt in records:
    sent = (fl & 0x01) == 0
    if len(pkt) < 5 or pkt[0] != 0x02: continue
    hf, ln = struct.unpack("<HH", pkt[1:5])
    conn = hf & 0x0FFF
    pb = (hf >> 12) & 0x3
    body = pkt[5:5+ln]
    R = reasm_tx if sent else reasm_rx
    key = (sent, conn)
    if pb in (0, 2):
        if len(body) < 4: continue
        l2_len, cid = struct.unpack("<HH", body[:4])
        att = body[4:]
        if len(att) < l2_len:
            R[key] = (cid, l2_len, att, ts); continue
    elif pb == 1:
        if key not in R: continue
        cid, l2_len, prev, t0 = R[key]
        att = prev + body
        if len(att) < l2_len: R[key] = (cid, l2_len, att, t0); continue
        del R[key]
    else: continue
    if cid != 0x0004: continue
    op = att[0]
    if ts0 is None: ts0 = ts
    t_rel = (ts - ts0)/1e6
    direction = "TX" if sent else "RX"
    if op in (0x52, 0x12, 0x1B):
        if len(att) < 3: continue
        att_handle = struct.unpack("<H", att[1:3])[0]
        val = att[3:]
        per_conn[conn][f"{direction} 0x{att_handle:04x}"] += 1
        per_conn_writes[(conn, att_handle, direction)].append((t_rel, val.hex().upper()))

# Print summary
print("Activity per connection:")
for conn in sorted(per_conn.keys()):
    print(f"\n=== Connection 0x{conn:04x} ===")
    total_tx = sum(c for k, c in per_conn[conn].items() if k.startswith("TX"))
    total_rx = sum(c for k, c in per_conn[conn].items() if k.startswith("RX"))
    print(f"  Total: TX={total_tx} RX={total_rx}")
    for k, c in per_conn[conn].most_common(20):
        print(f"  {k}: {c}")
