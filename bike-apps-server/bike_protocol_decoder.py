#!/usr/bin/env python3
"""
Bike-computer BLE protocol decoder
===================================
Parses an Android btsnoop_hci.log and decodes the proprietary binary
protocol used by the bike/fitness peripheral on top of the Nordic UART
Service (NUS, service UUID 6e400001-b5a3-f393-e0a9-e50e24dcca9e).

Frame format (both directions):
    byte 0        SOF        = 0xF9
    byte 1        CMD        command/response opcode
    byte 2        LEN        payload length (0-16 observed)
    byte 3..3+LEN PAYLOAD    LEN bytes
    byte 3+LEN    CHECKSUM   = sum(bytes[0 : 3+LEN]) & 0xFF   (simple 8-bit
                              additive checksum over SOF+CMD+LEN+PAYLOAD)
    remaining     PADDING    zero-padding, only seen on host->device WRITEs,
                              which are always sent as fixed-size 20-byte
                              ATT writes regardless of real payload length.
                              device->host NOTIFYs are exactly sized
                              (no padding).

Commands observed on handle 0x0011 (write) / notifications on 0x000e:
    0xD0 -> 0xE0   PING / HELLO            (no payload)
    0xD1 -> 0xE1   GET_DEVICE_INFO         (16-byte static payload: fw ver,
                                            build, model, etc. - unchanged
                                            across repeated calls)
    0xD3 -> (ack)  SET_CONFIG / SET_TIME   (13-byte payload, sent once after
                                            device info exchange)
    0xD4 -> 0xE4   GET_COUNT / START_SYNC  (starts the record-download loop)
    0xD5 -> 0xE5,0xE6,0xE7  READ_NEXT_RECORD (cursor-based "get next sample";
                                            same request payload sent
                                            repeatedly; device auto-advances
                                            an internal read pointer, low
                                            byte of E5 payload increments
                                            each call = record index)

Usage: python3 bike_protocol_decoder.py /path/to/btsnoop_hci.log
"""
import sys
import struct

SOF = 0xF9

CMD_NAMES = {
    0xd0: 'PING_REQ', 0xe0: 'PING_RSP',
    0xd1: 'DEVICE_INFO_REQ', 0xe1: 'DEVICE_INFO_RSP',
    0xd3: 'SET_CONFIG_REQ', 0xe3: 'SET_CONFIG_RSP',
    0xd4: 'START_SYNC_REQ', 0xe4: 'START_SYNC_RSP',
    0xd5: 'READ_NEXT_REQ', 0xe5: 'RECORD_A_RSP',
    0xe6: 'RECORD_B_RSP', 0xe7: 'RECORD_C_RSP',
}


def parse_btsnoop(path):
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:8] == b'btsnoop\x00', 'not a btsnoop file'
    offset = 16
    records = []
    while offset < len(data):
        orig_len, incl_len, flags, drops, ts = struct.unpack('>IIIIq', data[offset:offset + 24])
        offset += 24
        pkt = data[offset:offset + incl_len]
        offset += incl_len
        records.append({'flags': flags, 'data': pkt})
    return records


def decode_frame(b):
    """Decode one SOF/CMD/LEN/PAYLOAD/CHECKSUM frame. Returns None if not
    recognised as this protocol."""
    if len(b) < 4 or b[0] != SOF:
        return None
    cmd = b[1]
    ln = b[2]
    if 3 + ln + 1 > len(b):
        return None
    payload = b[3:3 + ln]
    checksum = b[3 + ln]
    calc = sum(b[0:3 + ln]) & 0xff
    padding = b[3 + ln + 1:]
    return {
        'cmd': cmd,
        'name': CMD_NAMES.get(cmd, f'0x{cmd:02x}'),
        'len': ln,
        'payload': payload,
        'checksum': checksum,
        'checksum_ok': calc == checksum,
        'padding': padding,
    }


def iter_att_events(records, want_conn_handle=None):
    """Yields (idx, direction, conn_handle, att_handle, value) for ATT
    WRITE_CMD (opcode 0x52) and HANDLE_VALUE_NOTIFICATION (opcode 0x1b)
    packets found in HCI ACL data."""
    for idx, r in enumerate(records):
        pkt = r['data']
        if not pkt or pkt[0] != 0x02:
            continue
        if len(pkt) < 5:
            continue
        handle_flags, data_len = struct.unpack('<HH', pkt[1:5])
        conn_handle = handle_flags & 0x0fff
        if want_conn_handle is not None and conn_handle != want_conn_handle:
            continue
        acl = pkt[5:5 + data_len]
        if len(acl) < 4:
            continue
        l2cap_len, cid = struct.unpack('<HH', acl[:4])
        if cid != 0x0004:  # ATT
            continue
        att = acl[4:4 + l2cap_len]
        if not att:
            continue
        opcode = att[0]
        rest = att[1:]
        if opcode in (0x1b, 0x52) and len(rest) >= 2:
            att_handle = struct.unpack('<H', rest[:2])[0]
            value = rest[2:]
            direction = 'NOTIFY' if opcode == 0x1b else 'WRITE'
            yield idx, direction, conn_handle, att_handle, value


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'btsnoop_hci.log'
    conn_filter = int(sys.argv[2], 16) if len(sys.argv) > 2 else None
    records = parse_btsnoop(path)
    for idx, direction, conn_handle, att_handle, value in iter_att_events(records, conn_filter):
        frame = decode_frame(value)
        if frame is None:
            print(f"{idx:5d} conn=0x{conn_handle:04x} {direction:6s} h=0x{att_handle:04x} RAW {value.hex()}")
            continue
        print(f"{idx:5d} conn=0x{conn_handle:04x} {direction:6s} h=0x{att_handle:04x} "
              f"{frame['name']:16s} payload={frame['payload'].hex():36s} "
              f"chk={'OK' if frame['checksum_ok'] else 'BAD'}")


if __name__ == '__main__':
    main()
