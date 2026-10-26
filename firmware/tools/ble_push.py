"""把一份真实待办快照按 campus-sync/1 推给设备，并等它回 ACK。

这是固件协议实现的端到端测试：分片写入 → 设备重组 → 解析 → 落 NVS → 回 ACK。
手机不通的时候，用它可以判断问题在固件还是在手机。

设备侧对 RX 特征是 WRITE_ENC|WRITE_AUTHEN，所以写入需要配对。连接时设备会主动发起
加密，Windows 可能弹配对框 —— 那时把设备屏上的 6 位配对码填进去即可。
"""
import asyncio
import struct
import sys
import time

from bleak import BleakClient, BleakScanner

SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

MAGIC = 0xA7
VERSION = 1
HEADER = 14
TYPE_SNAPSHOT = 0x02
TYPE_ACK = 0x05


def crc16(data: bytes) -> int:
    """CRC-16/CCITT-FALSE，与固件 campus_crc16 一致。"""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def frame(transfer_id: int, index: int, total: int, payload_crc: int, payload: bytes) -> bytes:
    return struct.pack("<BBBBHHHHH", MAGIC, VERSION, TYPE_SNAPSHOT, 0,
                       transfer_id, index, total, len(payload), payload_crc) + payload


SNAPSHOT = (
    "campus-sync/1\n"
    "T\t1\t1760000000000\t0\tactive\t微信\t26造价2班班级群\t提交数据结构实验报告\n"
    "T\t2\t0\t0\tpending\t钉钉\t辅导员\t开班会\n"
    "T\t3\t1760003600000\t0\tactive\t学习通\t课程通知\t本周实验课改到周四下午\n"
).encode("utf-8")


async def main() -> int:
    target = sys.argv[1] if len(sys.argv) > 1 else "4C:11:AE:2F:C0:9A"
    print(f"payload {len(SNAPSHOT)} 字节，整包 CRC 0x{crc16(SNAPSHOT):04X}")

    device = await BleakScanner.find_device_by_address(target, timeout=15.0)
    if device is None:
        print("找不到设备。")
        return 1

    acks = []

    def on_notify(_sender, data: bytearray) -> None:
        raw = bytes(data)
        if len(raw) >= HEADER and raw[0] == MAGIC:
            kind = raw[2]
            transfer = struct.unpack_from("<H", raw, 4)[0]
            body = raw[HEADER:HEADER + struct.unpack_from("<H", raw, 10)[0]]
            print(f"  <- 收到帧 type=0x{kind:02X} transfer={transfer} payload={body.hex()}")
            if kind == TYPE_ACK:
                acks.append((transfer, body[2] if len(body) >= 3 else None))
        else:
            print(f"  <- 收到无法识别的数据 {raw.hex()}")

    async with BleakClient(device, timeout=25.0) as client:
        print(f"已连接，MTU {client.mtu_size}")
        await client.start_notify(TX, on_notify)
        print("已订阅 TX 通知")

        chunk = max(1, client.mtu_size - 3 - HEADER)
        chunks = [SNAPSHOT[i:i + chunk] for i in range(0, len(SNAPSHOT), chunk)]
        whole_crc = crc16(SNAPSHOT)
        transfer = int(time.time()) & 0xFFFF
        print(f"分 {len(chunks)} 片，每片最多 {chunk} 字节，transfer={transfer}")

        for index, piece in enumerate(chunks):
            await client.write_gatt_char(RX, frame(transfer, index, len(chunks), whole_crc, piece),
                                         response=True)

        print("全部写完，等 ACK…")
        for _ in range(50):
            if acks:
                break
            await asyncio.sleep(0.1)

        if not acks:
            print("\n没等到 ACK。")
            return 2
        transfer_id, status = acks[0]
        names = {0: "OK", 1: "CRC 不符", 2: "解析失败"}
        print(f"\nACK transfer={transfer_id} status={status} ({names.get(status, '?')})")
        return 0 if status == 0 else 3


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
