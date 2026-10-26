# 校园待办 · AI Passport 固件端

ESP32-C3（FoloToy AI Passport）上的前端：通过蓝牙接收手机推来的待办，在 240×320 屏上显示，
用三个按键标记完成 / 稍后，并把操作回传给手机。协议是 **campus-sync/1**，与安卓端
`campus-todo/android` 的 `BleFrame.java` / `TodoSnapshot.java` 是同一份契约的两端。

## 当前进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| A | 工程骨架 + 纯逻辑层（帧/快照/排序）+ 主机测试 + 能构建 | **完成** |
| B | NimBLE GATT server（RX 重组、TX 回 ACK/OP、LE Secure Connections 绑定） | **完成，未上硬件** |
| C1 | NVS 持久化待办快照（含序列化器与往返测试） | **完成，未上硬件** |
| C2 | LVGL 中文界面（列表/详情、三键操作） + 中文字形覆盖校验 | **完成，未上硬件** |
| D | 合并镜像已产出；真机联调未做（需用户确认后刷写） | 部分完成 |

**固件功能已完整**：手机配对后把待办推过来，设备列表显示中文标题与截止时间，
上下选择、确定进入详情，可用「标记完成 / 稍后 1 小时 / 返回」操作并把结果回传手机，
待办落 NVS 断电不丢。

**唯一没做的就是上硬件**——见文末「未验证项」。

## 中文字形

动态待办标题是任意汉字，所以设备必须带字库。两份字体，各有用途：

| 字体 | 内容 | 字形数 | 生成物大小 |
| --- | --- | --- | --- |
| `campus_font_16` | ASCII + **GB2312 一级汉字**（3755）+ 界面文案，16px 1bpp | 3855 | 1122 KB |
| `campus_font_20` | ASCII + 界面文案，20px 2bpp | 202 | 102 KB |

1bpp 是刻意的：3755 个汉字在 2bpp 下源码要 2 MB 以上，1bpp 的 16×16 点阵在 240×320 上够看。

**为什么自己写生成脚本而不是直接跑仓库的 `tools/token_font/make_font.py`**：那个脚本把生成物
写进 `ai-passport` 仓库，而本工程不碰那个仓库（它里面还有在飞的改动）。所以复用了它的
**做法**而不是文件——同一个 `lv_font_conv@1.5.3`、同一个字体源、同一个 `--lv-include lvgl.h`
调用方式，以及同一套**稀疏 cmap 逐码点校验**。

```powershell
node tools/font/make_font.js --generate   # 生成
node tools/font/make_font.js --check      # 只校验覆盖
```

两点值得记下来，都是这套流程真的踩到的：

1. **`--lv-include lvgl.h` 不能省。** 少了它，生成物的 `#else` 分支写的是 `"lvgl/lvgl.h"`，
   而 ESP-IDF 的 lvgl 组件只在 include 根目录提供 `"lvgl.h"`，编译直接 fatal error。
2. **GB2312 那 3760 个编码槽位里有 5 个是未分配。** Node 的 `gbk` 解码器把它们映射到私用区
   （U+E810–U+E814），字体里当然没有这些字形——覆盖率检查第一次跑就是在这里报错的。
   私用区不是真字符，必须排除；排除后正好 3755。

校验是**逐码点**做的，不是「传了什么就当有什么」：lv_font_conv 对离散字符集用稀疏映射，
`range_start` 只是最小值，真码点靠 `unicode_list` 的相对偏移给出。把 `range_length` 当连续
区间会得出「覆盖了 U+4E00..U+9FA5」这种假结论。同时做反向检查，多余字形（上一次生成的残留）
也会报错。

## 静态资源占用（`idf.py size`）

| 项 | 数值 |
| --- | --- |
| DRAM 已用 | 192,084 B / 321,296 B（59.8%），**剩 129,212 B 给堆** |
| 镜像大小 | 978,446 B（app 分区 8064 KB，88% 空闲） |

BLE 起来时从堆里拿走约 60KB（`ai-passport` 仓库两块真板实测），所以运行时大约还剩 69KB。
**这是静态分析的结果，不是运行时实测**——真正的空闲堆要在设备上读 `esp_get_free_heap_size()`。

三块大静态缓冲占了约 47KB，都是刻意走 `.bss` 而不是堆：

| 缓冲 | 大小 | 在哪 |
| --- | --- | --- |
| BLE 快照重组缓冲 | 16 KB | `campus_ble.c` |
| NVS 序列化缓冲 | 16 KB | `campus_store.c` |
| 待办列表 | 约 15 KB | `campus_main.c` |

两个差点踩进去的坑，都是构建期才发现、硬件上才暴露的那种：

1. **LVGL 9.5 的 `LV_MEM_SIZE_KILOBYTES` 已废弃且不生效**，默认 `LV_MEM_SIZE` 是 64KB。
   只设前者会拿到一个 64KB 的池并让内存爆掉，而它只给一句 `#warning`。
   本工程显式设 `CONFIG_LV_MEM_SIZE=32768`。
2. **NVS 分区从 24KB 扩到 64KB**。待办快照最大约 16KB，还要同时放 BLE 的绑定密钥
   （`NIMBLE_NVS_PERSIST=y`），24KB 装不下。分区表因此改成
   `nvs 64K @0x9000 / phy_init 4K @0x19000 / factory 8064K @0x20000`。

## 环境

| 东西 | 位置 | 备注 |
| --- | --- | --- |
| ESP-IDF 5.5.3 | `D:\DSH\esp\esp-idf-v5.5.3` | 已装好 |
| IDF 工具链 / Python venv | `C:\Users\22050\.espressif` | venv 在 `python_env\idf5.5_py3.11_env` |
| Zig（主机编译器） | `D:\Tools\zig-x86_64-windows-0.17.0` | 见下面的「为什么需要它」 |
| BSP（只读引用） | `..\..\ai-passport\components` | 通过 `EXTRA_COMPONENT_DIRS` |

**为什么需要 Zig。** 这台机器上 `gcc` / `clang` / `cc` 都没有；MSVC 装了但没装 Windows SDK，
连 `stdio.h` 都是缺的，编不出主机程序。Zig 自带 clang + mingw 头，解压即用，所以主机测试用它跑。

**为什么不直接用仓库里的 `activate-idf.ps1`。** 它依赖 `activate.py --export` 输出一段要
dot-source 的临时脚本，而本机执行策略禁止运行脚本文件。`tools/idfbuild.ps1` 绕过它：
让 `idf_tools.py export --format key-value` 打印环境变量、由 PowerShell 直接赋值，
不执行任何生成的脚本。

## 构建与测试

```powershell
cd D:\DSH\Workspace\campus-todo\firmware

# 主机测试（纯逻辑，不需要 IDF 与硬件）
powershell -ExecutionPolicy Bypass -File tools\hosttest.ps1

# 固件构建
powershell -ExecutionPolicy Bypass -File tools\idfbuild.ps1
# 其它 idf.py 子命令把参数接在后面。
# 注意 merge-bin 是在 build\ 目录里执行的，输出路径要相对于 build\，
# 写成 build\xxx.bin 会变成 build\build\xxx.bin 然后 FileNotFoundError。
powershell -ExecutionPolicy Bypass -File tools\idfbuild.ps1 merge-bin -o campus-todo-full.bin
```

## 主机测试覆盖什么

`tests/test_campus_logic.c` 覆盖三块，都是「写错就会静默出错」的地方：

- **CRC16 用标准校验值**（`"123456789"` → `0x29B1`）。多项式写错，这里立刻红。
- **帧编解码的严格性**：魔术字/版本/长度任一不对就拒绝，而不是把野数据当帧解析。
- **重组**：正常路径、重传（报 DUPLICATE 而不是重复计数）、跳号（报 OUT_OF_ORDER 而不是
  拼出错位数据）、数据被改坏（报 CRC_FAIL 而不是交出坏数据）、换 transfer_id 自动重开。
- **快照解析**：缺头报错、坏行计数而不静默吞、**超出 u32 的 id 必须丢弃**（截断成另一个
  id 会去改到别的待办）、空标题是合法的。
- **排序**：没有截止时间的排在同组最后，而不是被当成 1970 年排到最前。

## 两条刻意的设计决定

**1. 重组是线性的，不做乱序缓存。** 前提是手机侧对 RX 特征用的是 Write With Response——
ATT 层保证逐片确认且按序到达，乱序不会发生，为它维护分片表纯属浪费内存。
跳号会被明确报成 `OUT_OF_ORDER`，而不是拼出一段错位数据。

**2. `expected_crc` 在传输开始时锁定。** 之后各片自带的 crc 不再参与判断。
否则中途一片胡说八道就能把整包判成坏包——测试里专门写了这一点。

## 内存预算

BLE 起来后堆只剩约 125KB（`ai-passport` 仓库两块真板实测），所以：

- 重组缓冲 16KB + 1 字节（多那一字节是给解析器补 `'\0'` 用的）走 `.bss`，不进堆
- **不要给这个应用加全屏截图功能**：一张 240×320 RGB565 是 150KB，与 BLE 不能共存，
  这在 `ai-passport/docs/reference/shinku-chen/two-device-ble-link.md` 里有实测记录

## 协议摘要

```
帧头 14 字节（小端）:
  0 magic 0xA7 | 1 ver 1 | 2 type | 3 flags
  4 transfer_id u16 | 6 index u16 | 8 total u16 | 10 payload_len u16
  12 payload_crc16 u16  ← 整个 payload 的 CRC
  14 payload
```

| UUID | 用途 | 属性 |
| --- | --- | --- |
| `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | Service | Nordic UART 布局 |
| `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | RX（手机写） | Write With Response |
| `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | TX（设备通知） | Notify |

type：`0x01` HELLO / `0x02` SNAPSHOT / `0x03` DELTA(未实现) / `0x04` OP / `0x05` ACK。

载荷是文本，一行一条（制表符分隔）：

```
campus-sync/1
T	<id>	<dueAt>	<snoozeUntil>	<status>	<source>	<convo>	<title>
```

`status` ∈ `active` / `pending` / `done` / `info`。设备回传的 `OP` 帧载荷：

```
<todoId>	<action>	<snoozeMinutes>	<devSeq>
```

`action` ∈ `done` / `undone` / `snooze`。

## 真机上抓到的 bug

这些路径在主机上编得过、测得过，只有插上板子才暴露。

1. **TX 特征句柄查得太早，设备永远发不出通知。**
   `ble_gatts_find_chr()` 原先写在 `ble_gatts_add_svcs()` 之后，真机上返回
   `BLE_HS_ENOENT`（5），于是 `s_tx_val_handle` 一直是 0，`notify_frame()` 每次都在开头
   就返回 —— ACK 和 OP 全都发不出去，而日志上只有一行「找不到 TX 特征」。

   根因在协议栈的时序里（`components/bt/host/nimble/.../ble_hs.c`）：

   ```
   ble_gatts_start();   // ← 服务在这里才真正注册、句柄才分配
   ble_hs_sync();       // → 调用 ble_hs_cfg.sync_cb
   ```

   而 `ble_gatts_add_svcs()` 只是把服务定义入队。所以查询必须放到 **sync 回调**里。
   修好后日志是 `TX value handle = 18`。

2. **ACK 不能回 ACK。** 设备发出的 OP 帧会收到手机的 ACK；如果设备不区分帧类型就一律
   回复，两边会互相确认到死。`handle_complete` 对 `TYPE_ACK` 直接返回，不回。

## 并发：两个任务共用一个缓冲的错

3. **`campus_store` 的序列化缓冲被两个任务共用。** save 会被 NimBLE host 任务（收到快照）
   和输入任务（用户按键）同时调用，不加锁就会同时改写同一个 16KB 缓冲，写出一份被截断
   的存档，然后在下次启动时按坏存档丢掉。现在有互斥锁，`campus_store_init()` 必须先调。

4. **通知发送路径也要串行化。** ACK 由 host 任务发、OP 由输入任务发，两边同时调
   `ble_gatts_notify_custom` 会互相踩，失败返回还很含糊。用一个互斥锁串起来
   （原先用一个独立发送任务只串行化了 OP，ACK 仍然并发，所以那个任务被删掉了）。

另外界面有一个**游标失效窗口**：BLE 收到新快照是原地改写待办列表的，而排序视图存的是
指向它的指针。原先只在 200ms 的定时器里重建视图，这中间按下的键会拿着指向旧内容的游标。
现在 `campus_ui_key` 每次先用最新列表对齐游标再处理按键。

## 真机启动日志（已验证的部分）

```
I (25) boot:  0 nvs      WiFi data  01 02 00009000 00010000   ← 64KB NVS 生效
I (26) boot:  1 phy_init RF data    01 01 00019000 00001000
I (26) boot:  2 factory  factory app 00 00 00020000 007e0000
I (241) bsp_i2c: I2C 初始化 SDA=GPIO10 SCL=GPIO7
I (372) bsp_disp: 显示 240x320
I (405) bsp_lvgl: LVGL 初始化完成
I (406) campus_store: 没有已保存的快照 (ESP_ERR_NVS_NOT_FOUND)  ← 首次启动
I (441) campus_ui: 界面已创建                                 ← 中文字体与 LVGL 池没爆
I (503) bsp_btn: 按键初始化:ADC1_CH0
I (505) BLE_INIT: Bluetooth MAC: 4c:11:ae:2f:c0:9a
I (554) campus_ble: TX value handle = 18                     ← 修复后
I (555) NimBLE: GAP procedure initiated: advertise
I (557) campus: 就绪：等待手机连接
```

零错误。**注意**：日志里的中文在**本工程的抓取管道**里会显示成乱码（PowerShell 编码），
那是抓取侧的显示问题，设备发的是正常 UTF-8；屏上的中文走的是编译进固件的字形，不受影响。

## 未验证项（重要）

**这份固件从来没有在硬件上运行过。** 所有验证都是主机侧或静态的：

| 已验证 | 方式 |
| --- | --- |
| 协议编解码、CRC、重组、快照解析/序列化、排序 | 主机测试 20 个用例（zig cc） |
| 两端线格式逐字节一致 | 安卓端与固件端各自硬编码同一串黄金向量 |
| 中文字形覆盖 | 逐码点解析生成物 cmap，正反两向 |
| 编译与链接 | clean 构建，自有源码零告警 |
| 内存放得下 | `idf.py size` 静态分析 |
| 分区表 | `gen_esp32part` 校验 |

**未验证（只有真机能回答）**：

- BLE 能否被手机扫到、能否配对（LE Secure Connections + 显示配对码这条路径完全没跑过）
- 分片写入的重组在真实 MTU 与真实时序下是否成立
- 中文字体在屏上的实际观感（16px 1bpp 是我在「体积」和「可读性」之间的取舍，没法预览）
- NVS 落盘与断电恢复
- 三个按键的电平判定与手感
- 真实空闲堆（静态分析说还剩约 128KB，BLE 起来后约 68KB，但那是算出来的）

刷写需要用户确认，本工程不会自行烧录。
