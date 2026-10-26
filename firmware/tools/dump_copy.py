"""把安卓端所有面向用户的中文字符串抽出来，按文件分组。

用途：集中审一遍 App 里到底有哪些文案，避免改一处漏一处 —— 上一轮
「设置里别点名具体应用」就是这种问题：同一句话在另一个页面还留了一份。

只抽含中文的字面量：纯 ASCII 的字符串基本是键名、格式串、日志 TAG，不是给用户看的。
"""
import re
import sys
from pathlib import Path

# 控制台默认是 GBK，遇到「¥」这类字符会 UnicodeEncodeError 直接崩。
# 输出固定成 UTF-8，重定向到文件时才不会半路断掉。
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 本脚本在 firmware/tools/ 下，仓库根是上两级
SRC = Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "java" / "dev" / "campus" / "todo"

# 页面名（给人看的），按 UI 上的顺序排，便于对照
ORDER = [
    ("MainActivity", "主页 · 班级通知（待办列表）"),
    ("InfoActivity", "通知页"),
    ("InfoAdapter", "通知页 · 列表项"),
    ("TodoAdapter", "待办列表项"),
    ("RawListActivity", "原始消息页"),
    ("RawAdapter", "原始消息 · 列表项"),
    ("RulesActivity", "黑白名单页"),
    ("DeviceActivity", "设备页（蓝牙同步）"),
    ("SettingsActivity", "设置页"),
    ("ShareActivity", "分享补录页"),
    ("Ui", "通用样式与映射"),
    ("Sample", "演示数据"),
    ("BleClient", "蓝牙 · 界面提示"),
    ("NotifListener", "通知采集"),
    ("AutoOrganizer", "自动整理"),
    ("Reminder", "提醒"),
    ("Organizer", "整理流程"),
    ("DeepSeek", "模型调用"),
    ("Filter", "噪声过滤"),
    ("InfoFallback", "通知兜底"),
    ("Extract", "抽取校验"),
    ("Rules", "规则判定"),
    ("InfoFeed", "通知分页"),
    ("Model", "数据模型"),
    ("TodoSnapshot", "快照编解码"),
    ("AutoJobService", "自动整理 · 后台任务"),
    ("BootReceiver", "开机重排提醒"),
]

LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
CJK = re.compile(r"[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]")


def unescape(text: str) -> str:
    return text.replace('\\n', '\n').replace('\\t', ' · ').replace('\\"', '"')


def collect(path: Path):
    out, seen = [], set()
    for raw in LITERAL.findall(path.read_text(encoding="utf-8")):
        if not CJK.search(raw):
            continue
        text = unescape(raw)
        if text in seen:
            continue
        seen.add(text)
        out.append(text)
    return out


def main() -> int:
    if not SRC.is_dir():
        print(f"找不到源码目录 {SRC}")
        return 1

    known = {name for name, _ in ORDER}
    files = sorted(SRC.glob("*.java"), key=lambda p: (
        next((i for i, (n, _) in enumerate(ORDER) if n == p.stem), len(ORDER)), p.stem))

    lines = []
    total = 0
    for path in files:
        strings = collect(path)
        if not strings:
            continue
        total += len(strings)
        title = next((t for n, t in ORDER if n == path.stem), path.stem)
        mark = "" if path.stem in known else "  （未在清单里，请补 ORDER）"
        lines.append(f"\n## {title}  ·  {path.name}{mark}")
        lines.extend(f"- {s}" for s in strings)
    lines.append(f"\n—— 共 {total} 条")

    # 自己写文件：PowerShell 5.1 的 `>` 重定向默认是 UTF-16LE，
    # 下游按 UTF-8 读会当成二进制。别把编码交给 shell。
    if len(sys.argv) > 1:
        Path(sys.argv[1]).write_text("\n".join(lines), encoding="utf-8")
        print(f"写入 {sys.argv[1]}：{total} 条，{len(lines)} 行")
    else:
        print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
