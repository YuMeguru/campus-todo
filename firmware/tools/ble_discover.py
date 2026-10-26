"""连上设备并列出 GATT 服务与特征 —— 验证 campus-sync/1 的服务端在空中是真的。

只做连接 + 服务发现，不写特征，所以不需要配对。用来把「固件里的 GATT 服务」和
「PC/手机能看到的 GATT 服务」对齐；扫不到或特征不对时，这就是第一道分界线。

注意：设备在连接时会主动发起加密，Windows 可能弹配对框。不理会即可 ——
服务发现本身不需要加密，结果照样有效。
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

EXPECTED = {
    "6e400002-b5a3-f393-e0a9-e50e24dcca9e": "RX（手机写）",
    "6e400003-b5a3-f393-e0a9-e50e24dcca9e": "TX（设备通知）",
}


async def main() -> int:
    target = sys.argv[1] if len(sys.argv) > 1 else "4C:11:AE:2F:C0:9A"
    print(f"扫描并连接 {target} …")
    device = await BleakScanner.find_device_by_address(target, timeout=15.0)
    if device is None:
        print("找不到设备。")
        return 1
    print(f"找到：{device.name or '(无名)'}  {device.address}")

    async with BleakClient(device, timeout=20.0) as client:
        print(f"已连接：{client.is_connected}\n")
        found = {}
        for service in client.services:
            print(f"服务 {service.uuid}")
            for char in service.characteristics:
                props = ",".join(char.properties)
                print(f"    特征 {char.uuid}  [{props}]  handle={char.handle}")
                if char.uuid.lower() in EXPECTED:
                    found[char.uuid.lower()] = props

        print()
        ok = True
        for uuid, role in EXPECTED.items():
            props = found.get(uuid)
            if props is None:
                print(f"MISSING {role} {uuid}")
                ok = False
                continue
            print(f"OK {role} {uuid}  [{props}]")
        if ok:
            print("\n结论：GATT 服务在空中可见，特征与属性与设计一致。")
        return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
