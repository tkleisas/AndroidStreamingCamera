"""Analyze DUML frames written by Mimo to handle 0x002b."""
import re
from collections import Counter

with open("parsed.txt") as f:
    lines = f.readlines()

# Match TX writes to 0x002b that look like DUML (start with 55)
duml = []
for line in lines:
    m = re.match(r"TX WriteCmd handle=0x002b \((\d+)B\): (55[0-9A-F]+)", line.strip())
    if not m:
        continue
    n = int(m.group(1))
    hex_str = m.group(2)
    if len(hex_str) != n * 2:
        continue
    b = bytes.fromhex(hex_str)
    if len(b) < 13:
        continue
    sender = b[4]
    receiver = b[5]
    seq = b[6] | (b[7] << 8)
    flags = b[8]
    cmd_set = b[9]
    cmd_id = b[10]
    payload = b[11:-2]
    duml.append((n, sender, receiver, seq, flags, cmd_set, cmd_id, payload, hex_str))

print(f"Total DUML TX frames: {len(duml)}")
print()

# Tally (receiver, cmdSet, cmdId) combinations
tally = Counter((r, cs, ci) for _, _, r, _, _, cs, ci, _, _ in duml)
print("Top receiver/cmdSet/cmdId combinations:")
for (r, cs, ci), count in tally.most_common(20):
    print(f"  recv=0x{r:02X} cmdSet=0x{cs:02X} cmdId=0x{ci:02X}  count={count}")

# Find rare commands (likely the actual user-triggered actions)
print()
print("Rare commands (count <= 3) — likely user actions:")
for i, (n, snd, rcv, seq, fl, cs, ci, pl, hx) in enumerate(duml):
    if tally[(rcv, cs, ci)] <= 3:
        print(f"  [{i:4d}] recv=0x{rcv:02X} set=0x{cs:02X} id=0x{ci:02X} pl={pl.hex().upper()} ({len(pl)}B)  full={hx}")
