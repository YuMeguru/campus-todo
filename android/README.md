# 校园待办 · 安卓整合 Demo

一个自包含的安卓应用：**后端**（通知采集 → 本地库 → DeepSeek 提炼 → 待办存储 → 本地提醒）
和**前端**（一键整理、待办列表与详情、完成/稍后/删除、设置页）都在同一个 App 里。

- 包名 `dev.campus.todo`，版本 `0.1.0-demo`
- `minSdk 31`（Android 12）/ `targetSdk 36`
- **运行时第三方依赖：0 个**。全部用 framework API（`NotificationListenerService`、
  `SQLiteOpenHelper`、`HttpURLConnection`、`org.json`、`AlarmManager`、原生 View）。
  只有 `testImplementation` 用了 JUnit 和一份 JVM 版 `org.json`，它们不进 APK。

---

## 1. 环境（已经装好，都在 D 盘）

本机 `dl.google.com` 与 `github.com` 都不可达，所以没用官方 `sdkmanager`，改成镜像手工落盘：

| 组件 | 路径 | 来源镜像 |
| --- | --- | --- |
| JDK 17.0.20.1 | `D:\Android\jdk` | 清华 Adoptium |
| Android SDK（platform-tools / platforms;android-36 / build-tools;36.0.0 / cmdline-tools） | `D:\Android\sdk` | 腾讯 AndroidSDK |
| Gradle 8.14.3 | `D:\Android\gradle-dist\gradle-8.14.3` | 腾讯 Gradle |
| Gradle 缓存 / AGP 依赖 | `D:\Android\gradle` | 阿里云 Google Maven |

重装只需跑一次 `..\android-setup.ps1`（幂等）。

## 2. 构建

```powershell
$env:JAVA_HOME       = 'D:\Android\jdk'
$env:ANDROID_HOME    = 'D:\Android\sdk'
$env:GRADLE_USER_HOME= 'D:\Android\gradle'
$env:PATH            = "$env:JAVA_HOME\bin;$env:PATH"

cd D:\DSH\Workspace\campus-todo\android
& 'D:\Android\gradle-dist\gradle-8.14.3\bin\gradle.bat' assembleDebug testDebugUnitTest
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 3. 装到手机

```powershell
& 'D:\Android\sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
```

手机需先打开「开发者选项 → USB 调试」。也可以直接把 `app-debug.apk` 拷进手机点击安装
（需要允许安装未知来源）。

## 4. 演示步骤

**第一次跑，不需要微信通知也能看到整条链路：**

1. 打开 App → 「设置」→ **插入示例消息**（4 条：微信班群实验报告、钉钉班会、学习通测试、室友闲聊）。
2. 回首页 → 顶部会显示「待整理消息 4 条」。
3. 点 **一键整理待办**。
   - 没填 API Key 会明确提示「还没有填 API Key」，不会静默失败。
   - 先在设置里把 DeepSeek 的 Key 粘进去（存在 Android Keystore 加密后的私有文件里）。
4. 整理完成后首页出现待办：标题、截止时间、来源（微信/钉钉/学习通）、类型。
   - 室友那条「晚上吃啥」不会变成待办 —— 模型会把它放进 `ignored`。
   - 时间或置信度不足的进「待确认」（橙色），**不自动排提醒**。
5. 点一条待办看详情：原文依据、置信度、为什么是待确认。
6. 长按列表项 = 直接标记完成；详情页里有 **完成 / 稍后 1 小时 / 删除**。
   稍后只推迟**提醒**，列表里会显示「已稍后 · 提醒 MM-dd HH:mm」，截止时间不变。
7. 「采集到的消息」页可以看到每条通知的状态：待整理 / 已过滤 / 已整理 —— 用来证明
   采集和过滤真的在工作。
8. 不想每次手点：**设置 → 自动整理 → 每 30 分钟**，之后它会自己跑（首页状态栏会显示档位）。
9. 想屏蔽某个群/联系人：**设置 → 采集规则 → 添加黑名单**，填群名里的一段字即可；
   加完再让那个群发条消息，规则页顶部的「已拦下 N 条」会 +1，而「采集到的消息」页里不会有它。

**接真实通知：**设置 → 「去开启通知使用权」→ 在系统列表里勾上「校园待办」。
之后微信/钉钉/学习通的通知会自动入库，回首页点一键整理即可。

**手动补录：**在微信里长按一条消息 → 分享 → 选「校园待办」；或在任意输入框选中文字 →
「处理文本」→「校园待办」。静音群和历史消息只能靠这条路。

## 5. 能做什么 / 不能做什么

能：

- 采集微信、钉钉、学习通的通知（白名单，其它应用一律不看）
- 本地过滤：验证码、支付收据、营销标题、过短、重复通知
- 一键批量整理（默认 10 条一批），DeepSeek `deepseek-flash` 出结构化 JSON
- **不信任模型**：`evidence` 必须在原文里找到（找不到直接丢弃）；相对日期以**通知到达时间**
  为基准，且相对日信号同时看 `due_text` 与 `evidence`（模型留空 `due_text` 也绕不过去）；
  日期越界或冲突就降级为待确认；置信度 < 60% 也进待确认
- 本地 SQLite 存待办，`AlarmManager` 排提醒（提前一天 + 截止时刻），重启后自动重排
- **「稍后 1 小时」只推迟提醒，不改截止时间**（截止时间是消息里的事实）
- **出网只允许 `api.deepseek.com` 一个域名**，地址不可配置，重定向被拒
- 每日费用硬闸：60 次**实际请求** / 30 万输入 token / 6 万输出 token（退避重试也计入请求数）
- **自动整理**：JobScheduler 定时（15 分钟 ~ 6 小时可选），只在联网时唤醒，没有新消息不发请求
- **采集规则**：黑名单优先、白名单限定，按会话名归一化子串匹配；命中的消息正文不入库
- **班级通知分区**：教室安排、调课、考试范围这类参考信息单独存一页，和待办分开，不排提醒
- **待办可编辑**：标题 / 截止时间 / 课程都能改；「待确认」给个时间就升级成正式待办并开始提醒
- **设备同步（campus-sync/1）**：蓝牙把未完成的待办推给 AI Passport，并接收设备上的完成/稍后操作
- **统一深色界面**：强制 `Theme.DeviceDefault`（深色），深底 + 白字 + 浅灰次要文字，
  不跟随系统的亮/暗设置。所有颜色集中在 `Ui.java`，界面代码里没有散落的硬编码色值

不能（这些是平台边界，不伪装）：

- 静音/免打扰群、开了「隐藏通知内容」的会话、装 App 之前的历史消息 → 采不到
  （注意：这里说的是**微信自己的**「消息免打扰」，系统「勿扰模式」不影响采集）
- 图片、语音、文件类通知只有标题，没有正文
- 被系统省电策略杀掉期间的通知会漏（这一版没有常驻前台服务）
- 断网时新消息只能排队等下次整理

## 6. 代码结构

```
app/src/main/java/dev/campus/todo/
  Model.java        纯数据（Rule / Raw / Todo）
  Filter.java       [纯逻辑] 应用白名单、噪声过滤、归一化、内容指纹
  Rules.java        [纯逻辑] 采集规则：黑名单优先、白名单限定、归一化子串匹配
  BleFrame.java     [纯逻辑] campus-sync/1 链路帧：帧头编解码、CRC16、分片与重组装配器
  TodoSnapshot.java [纯逻辑] 快照与设备操作的文本编解码（含分隔符转义）
  Extract.java      [纯逻辑] 模型 JSON → 待办，含全部硬校验
  DeepSeek.java     [纯逻辑] 请求体构造、错误映射、指数退避
  Prefs.java        少量开关 + 自动整理档位 + 拦截计数
  Db.java           SQLiteOpenHelper，raw / todo / run / rule 四张表（v3）
  KeyVault.java     Android Keystore + AES/GCM 包裹 API Key
  Organizer.java    整理主流程：取批 → 调用 → 校验 → 落库 → 排提醒（带单飞锁）
  AutoOrganizer.java  [纯逻辑] 自动整理的定时策略与档位文案
  AutoJobService.java JobScheduler 周期任务（自动整理）
  NotifListener.java  NotificationListenerService（采集 + 规则拦截）
  BleClient.java      手机侧 GATT 中央端（扫描/连接/MTU/订阅/分片写/等 ACK/重传）
  DeviceActivity.java 设备同步页（状态 + 日志，方便固件联调排障）
  Reminder.java / ReminderReceiver.java / BootReceiver.java  提醒
  ShareActivity.java  分享/处理文本补录入口
  MainActivity.java / SettingsActivity.java / RawListActivity.java
  RulesActivity.java / InfoActivity.java / InfoFeed.java
  TodoAdapter.java / InfoAdapter.java / RawAdapter.java / Ui.java  前端

app/src/test/java/dev/campus/todo/
  FilterTest.java / RulesTest.java / ExtractTest.java / DeepSeekTest.java
```

标 `[纯逻辑]` 的类不碰 Android API，`gradle testDebugUnitTest` 在 PC 上直接跑，
不需要模拟器。

## 6b. 后来的三个功能

**自动整理**（设置 → 自动整理 → 选择整理间隔）

用平台自带的 `JobScheduler`，不引 WorkManager、不需要前台服务与常驻通知。
档位：关闭（默认）/ 每 15 分钟 / 30 分钟 / 1 小时 / 3 小时 / 6 小时——
15 分钟是 JobScheduler 对周期任务的硬下限，写更小会直接抛异常。

三个省钱/省电前提：只在**联网**时唤醒；**没有新消息时 `Organizer` 直接返回、不发请求**；
撞到每日请求上限就停下。另外自动与手动共用一把单飞锁，不会同时打两次 API。

注意：在系统设置里「强行停止」应用会让已排期的任务失效，重新打开一次应用即可恢复。
被用户强停后按 Android 的设计不会自动重启任务。

**采集规则**（设置 → 采集规则）

按**会话名**匹配（通知的 `EXTRA_TITLE` 与 `EXTRA_CONVERSATION_TITLE`），
匹配前先做归一化（全角转半角、去掉所有空白、转小写），然后**包含**判断：

- 「计科 2201」与「计科2201」等价；规则写「班群」能命中「计科2201班群」
- 黑名单**一票否决**，白名单救不回来
- 白名单一旦有内容，就**只**采集命中的会话
- 命中的消息**正文不入库**，只累加一个计数（规则页顶部能看到「已拦下 N 条」）

一个必须说清的边界：微信群里通知的标题是**群名**而不是发言人，所以
「只屏蔽群里的某个人」做不到，只能整个群屏蔽；私聊按联系人名屏蔽是正常的。
规则的空条目会被忽略，否则一条空白黑名单会把所有消息干掉。

**班级通知分区**（主页底部「班级通知」，按钮上带条数）

教室安排、调课、考试范围这类内容，模型会用 `kind="info"` 标出来，走单独一条路：
**只过 `evidence` 防幻觉闸门**，不判日期、不判置信度、强制 `dueAt = 0`、不排提醒、
不进待办列表，也不去占「待确认」的位置。

它存在同一张表里（`status='info'`）。数据库升到 v4，只加了一列 `convo`（会话名/群名）：
把「群名 + 正文」拼成一个字符串会让正文被截掉一半，而且在列表里看不出是哪个群发的。
迁移走 `ALTER TABLE`，不 DROP。

**通知页是一份按天分组的信息流**，不是几行纯文本：

- 日期小标题（今天 / 昨天 / MM-dd）+ 深色卡片
- 卡片第一行：来源色标（微信绿 / 钉钉蓝 / 学习通橙）+ 群名 + 时间
- 正文预览最多 4 行，超出省略；点开是全文
- 长按直接删，详情弹窗里也能删，底部可一次清空（只清这个分区，不动待办）
- 分组逻辑在同名的 `InfoFeed.java` 里（纯逻辑），有单测钉住跨零点、跨天顺序、空列表

为什么必须分开：这类消息的用法是「以后回来查」，不是打勾。而且一条「@所有人」公告
常常塞了好几个班的安排，硬当待办提取会裂成好几条跟你无关的条目。

**本地兜底词表**（采集规则页底部能看到完整词表）

模型没提取、但正文命中词表（教室、自习、调课、考场、放假、报到、宿舍……）的通知，
会被**本地直接保留**成班级通知，不会被静默丢掉。首页结果行会标出其中多少条是兜底救回来的。

为什么加它：把「教室改到 313」交给一次模型判断，就是给了它被丢掉的机会。兜底把这一类
从「大概率保留」变成「一定保留」。代价的不对称很明显——误判最坏是多一条 info（不提醒、
不进待办、可删），漏判是走错教室。

它的天花板也说清楚：靠的是一份**小而稳定**的词表，覆盖班级后勤通知这类固定话术；
全新形状的消息救不了，那仍然是模型判断的地盘。词表刻意不用「楼」「课」这种单字，
否则「楼下等你」也会被捞进来（有单测钉住这一点）。

仍然可能丢的情况：模型没标 `info`、也没命中词表，被直接扔进 `ignored`。
判断依据看首页那行结果——有没有「班级通知 N 条」。

**编辑待办 / 待确认的出口**

详情页的操作是**自绘按钮行**（完成 / 编辑 / 稍后 / 删除）—— `AlertDialog` 只有三个按钮槽，
放不下四个动作。

「编辑」能改标题、截止时间、课程。截止时间用系统的日期 + 时间选择器，没有手输格式的解析风险；
另外有「清除时间」。

这也是**「待确认」唯一的出口**：给它一个确定时间，它就从待确认升级为正式待办并开始排提醒；
不给时间就继续留在待确认。规则写在 `Model.Todo.statusAfterEdit()` 里并有单测（`TodoStateTest`），
没有埋在 Activity 中——因为一旦写错，用户会**再也无法**把模型不敢确定的条目变成正式待办，
而且不会有任何报错。已完成的条目也不会因为改了个错别字被「复活」。

保存时会**先撤掉旧闹钟、再按新值重排**，否则提醒会响在改动前的时间点。

## 6c. 主线程上的三笔开销（已修）

界面卡顿的根因不是布局，是主线程被同步调用堵住：

1. **Keystore 跨进程调用**（最主要）。`MainActivity.refresh()` 与 `SettingsActivity.reload()`
   每轮都问一次 `KeyVault.has()`，而它原来每次都真的走 Android Keystore：跨进程 IPC +
   AES-GCM 解密，几十毫秒很常见。偏偏 `refresh()` 在 `onResume()` 和**每次勾选 / 稍后 / 删除之后**
   都会跑 —— 一按就卡一下。现在 Key 缓存在内存里（`save` / `clear` 会同步缓存），
   只有进程内第一次会真的解密。
2. **每次 bind 都 `new SimpleDateFormat`**。`Ui.dueLabel` / `timeLabel` 在滚动时每帧被调用十几次，
   而它的构造要做 Locale / NumberFormat 初始化。已换成静态的 `DateTimeFormatter`
   （不可变、线程安全，可以共享）。
3. **主页每次刷新 4 条独立 SQL**。两个计数合并成 `homeCounts()` 一条查询；原始消息页的
   「待整理 / 已过滤 / 已整理」也合成 `rawCounts()` 一条。

另外原始消息页原来是把最多 200 条先拼成字符串数组再交给 `ArrayAdapter` —— 一进页面就在主线程
把活全干完。现在走 `RawAdapter` 懒加载，每行只在要显示时才组装。

**这个诊断是读代码得出的，不是在设备上 profile 出来的**（当时没有连手机）。
如果装上去还是卡，下一步该量而不是猜：`adb shell dumpsys gfxinfo dev.campus.todo`
能给出每一帧的实际耗时。

## 6d. 设备同步（campus-sync/1）

**先说清楚：AI Passport 侧的固件还没实现这个协议。** 所以现在装上去点「扫描并同步」，
结果是「扫描超时：没找到设备」——这是预期行为，不是 bug。这一版做的是**手机侧通路 + 排障信息**，
让固件的实现者有东西可对（设备页有完整日志）。

协议（PLAN.md §4.4 的落地）：

| 用途 | UUID | 属性 |
| --- | --- | --- |
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | Nordic UART 布局（ai-passport 仓库已实测） |
| RX（手机→设备） | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | Write With Response |
| TX（设备→手机） | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Notify，CCCD `00002902-...` |

帧：14 字节头（magic `0xA7` / ver 1 / type / flags / transfer_id u16 / index u16 / total u16 /
payload_len u16 / **整包 CRC16** u16）+ 载荷，小端。type 有 HELLO / SNAPSHOT / DELTA(未实现) /
OP / ACK。

载荷是文本，一行一条，制表符分隔：

```
campus-sync/1
T	<id>	<dueAt>	<snoozeUntil>	<status>	<source>	<convo>	<title>
```

字段里的制表符与换行会被换成空格——否则一条带换行的公告能把整包拆成两条假记录。
这是这个格式唯一的硬约束，有单测钉住。

几个关键决定：

- **手机→设备用 `Write With Response`**：ATT 层本来就逐片确认，应用层不需要自己造滑动窗口；
  真正的完整性判据是整包 CRC，由设备收齐后校验并回 ACK。
- **一次只发一个 GATT 操作**：Android 的 GATT 栈不支持并发，第二个写会直接返回 false 而静默丢片。
  ACK 走**独立的插队队列**，免得在推快照的中途把没发完的分片清掉。
- 等不到 ACK 8 秒重传，最多 3 次；设备回 `ACK_CRC_FAIL` 也重传。
- 加密特征首次访问会触发系统配对；配对完成的广播会接着往下走，不用再点一次。
- 超时：扫描 15s / 连接 15s / ACK 8s。

只推**未完成**的待办：设备屏只有 240×320，已完成条目推过去只是噪音；手机上标完成的会因为它
不在快照里而从设备上消失——这正是想要的界面结果。

设备回传的操作（`OP` 帧：`<todoId>	<action>	<snoozeMinutes>	<devSeq>`）会落到本地库并重排提醒。
找不到对应待办时回**失败 ACK** 而不是静默吞掉——静默会让设备以为操作生效了，两边状态永久不一致。

**未验证的部分**：GATT 通路（扫描 / 连接 / MTU / 订阅 / 重传）一条都没在真机上跑过，
因为没有对端可连。纯协议层有 22 个单测（`BleFrameTest` 11 + `TodoSnapshotTest` 11），
CRC 用的是标准校验值 `0x29B1`（`"123456789"`）。

## 7. 费用

`deepseek-flash` 每 100 万 token：输入未命中缓存 $0.30（峰）/ $0.15（闲），
命中缓存 $0.006 / $0.003，输出 $1.20 / $0.60。
峰时是北京时间 09:00–12:00 与 14:00–18:00（正好撞上课时间），晚上便宜一半。

按 10 条一批估算，**一次整理约 ¥0.01**；一天 30 次约 ¥0.3，月约 ¥10。
硬闸算出的最坏情况月天花板约 ¥35。system prompt 逐字节固定，是为了命中上下文缓存。
