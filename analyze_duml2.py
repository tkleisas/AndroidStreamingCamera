"""Find DUML frames whose payload VARIES (likely motion commands)."""
import re
from collections import defaultdict

with open("parsed.txt") as f:
    lines = f.readlines()

duml = []
for idx, line in enumerate(lines):
    m = re.match(r"TX WriteCmd handle=0x002b \((\d+)B\): (55[0-9A-F]+)", line.strip())
    if not m: continue
    n = int(m.group(1))
    hex_str = m.group(2)
    if len(hex_str) != n * 2: continue
    b = bytes.fromhex(hex_str)
    if len(b) < 13: continue
    duml.append({
        "idx": idx,
        "line": len([l for l in lines[:idx+1] if l.strip()]),
        "len": n,
        "sender": b[4], "receiver": b[5], "seq": b[6] | (b[7] << 8),
        "flags": b[8], "cmd_set": b[9], "cmd_id": b[10],
        "payload": b[11:-2], "full": hex_str,
    })

# Group by (receiver, cmd_set, cmd_id), check payload variety
groups = defaultdict(list)
for f in duml:
    groups[(f["receiver"], f["cmd_set"], f["cmd_id"])].append(f)

print("Commands with VARYING payloads (likely motion or telemetry):")
print()
for (r, cs, ci), frames in sorted(groups.items(), key=lambda x: -len(x[1])):
    payloads = set(f["payload"] for f in frames)
    if len(payloads) > 1:
        print(f"recv=0x{r:02X} set=0x{cs:02X} id=0x{ci:02X}  count={len(frames)} unique_payloads={len(payloads)}")
        # Show first few unique payloads
        seen = []
        for f in frames:
            if f["payload"] not in seen:
                seen.append(f["payload"])
                print(f"   [#{f['line']:4d}] pl={f['payload'].hex().upper()} ({len(f['payload'])}B)")
                if len(seen) >= 8: break
        print()
