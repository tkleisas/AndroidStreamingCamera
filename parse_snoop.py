"""Parse btsnoop_hci.log and extract ATT writes (GATT writes)."""
import struct
import sys

LOG = sys.argv[1] if len(sys.argv) > 1 else "btsnoop_hci.log"

with open(LOG, "rb") as f:
    data = f.read()

# Header is 16 bytes
assert data[:8] == b"btsnoop\x00", "Not a btsnoop file"
version, datalink = struct.unpack(">II", data[8:16])

pos = 16
records = []
while pos + 24 <= len(data):
    orig_len, incl_len, flags, drops = struct.unpack(">IIII", data[pos:pos+16])
    ts = struct.unpack(">Q", data[pos+16:pos+24])[0]
    pos += 24
    pkt = data[pos:pos+incl_len]
    pos += incl_len
    records.append((flags, ts, pkt))

# Walk records, looking for ATT Write Command (0x52) and Write Request (0x12)
# HCI H4: first byte = packet type. 0x02 = ACL data
# ACL header: handle(2) length(2)
# L2CAP header: length(2) cid(2). cid=0x0004 = ATT
# ATT: opcode(1) handle(2) value(...)

# Track L2CAP reassembly per handle
reassembly = {}

def parse_acl(pkt, sent):
    if len(pkt) < 5: return
    pkt_type = pkt[0]
    if pkt_type != 0x02:  # ACL only
        return
    handle_flags, length = struct.unpack("<HH", pkt[1:5])
    handle = handle_flags & 0x0FFF
    pb = (handle_flags >> 12) & 0x3  # packet boundary flag
    body = pkt[5:5+length]

    if pb == 0x2 or pb == 0x0:  # start of L2CAP
        if len(body) < 4: return
        l2_len, cid = struct.unpack("<HH", body[:4])
        att_data = body[4:]
        if len(att_data) < l2_len:
            reassembly[handle] = (cid, l2_len, att_data)
            return
    elif pb == 0x1:  # continuation
        if handle in reassembly:
            cid, l2_len, prev = reassembly[handle]
            att_data = prev + body
            if len(att_data) < l2_len:
                reassembly[handle] = (cid, l2_len, att_data)
                return
            del reassembly[handle]
        else:
            return
    else:
        return

    if cid != 0x0004:  # ATT only
        return

    if len(att_data) < 1: return
    opcode = att_data[0]
    direction = "TX" if sent else "RX"
    if opcode == 0x52:  # Write Command
        if len(att_data) >= 3:
            att_handle = struct.unpack("<H", att_data[1:3])[0]
            value = att_data[3:]
            print(f"{direction} WriteCmd handle=0x{att_handle:04x} ({len(value)}B): {value.hex().upper()}")
    elif opcode == 0x12:  # Write Request
        if len(att_data) >= 3:
            att_handle = struct.unpack("<H", att_data[1:3])[0]
            value = att_data[3:]
            print(f"{direction} WriteReq handle=0x{att_handle:04x} ({len(value)}B): {value.hex().upper()}")
    elif opcode == 0x1B:  # Handle Value Notification
        if len(att_data) >= 3:
            att_handle = struct.unpack("<H", att_data[1:3])[0]
            value = att_data[3:]
            print(f"{direction} Notify   handle=0x{att_handle:04x} ({len(value)}B): {value.hex().upper()}")

for flags, ts, pkt in records:
    sent = (flags & 0x01) == 0  # bit 0: 0=sent (host->controller), 1=received
    parse_acl(pkt, sent)
