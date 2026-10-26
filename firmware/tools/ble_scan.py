"""从 PC 侧扫 BLE，把发现结果连服务 UUID 一起打出来。

用途：手机扫不到设备时，先确定「设备到底有没有在广播、广播的 UUID 是不是我们期望的那个」。
没有这一步就是在猜。同时也充当 campus-sync/1 的 PC 端对端测试对端（PLAN.md 的 M3）。
"""
import asyncio
import sys

from bleak import BleakScanner

EXPECTED = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"


async def main() -> int:
    timeout = float(sys.argv[1]) if len(sys.argv) > 1 else 12.0
    print(f"扫描 {timeout:.0f} 秒…")
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)

    if not found:
        print("没有发现任何 BLE 设备。")
        print("→ 可能是 PC 蓝牙没开，或设备根本没在广播。")
        return 1

    print(f"发现 {len(found)} 个设备：\n")
    hit = False
    for addr, (device, adv) in sorted(found.items()):
        name = device.name or adv.local_name or "(无名)"
        services = list(adv.service_uuids or [])
        mark = " <== MATCH" if EXPECTED in services else ""
        print(f"  {name:<22} {addr}  RSSI {adv.rssi:>4}{mark}")
        if services:
            for s in services:
                print(f"      服务 {s}")
        if EXPECTED in services:
            hit = True

    print()
    if hit:
        print(f"结论：设备在广播，且服务 UUID 与期望一致（{EXPECTED}）。")
        print("→ 设备侧没问题，问题在手机侧（扫描权限 / 定位开关 / 系统省电策略）。")
        return 0
    print(f"结论：扫到了设备，但没有任何一个广播 {EXPECTED}。")
    print("→ 设备侧广播内容不对，需要看固件里 UUID 的解析结果。")
    return 2


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
