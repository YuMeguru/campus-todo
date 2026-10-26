# 校园待办

仅支持安卓端。

把微信、QQ、钉钉、学习通这些应用的通知收下来，交给 DeepSeek 整理成待办列表。

## 怎么工作的

用 Android 的 `NotificationListenerService`，也就是系统设置里那个"通知使用权"。开了之后，这些应用每弹一条通知，系统都会递给它一份。

攒够十条，打包发给 DeepSeek，让它判断哪条是真待办、截止时间是什么时候。模型给的时间要跟原文对得上，对不上的进"待确认"，不排提醒。

不引第三方 SDK，也不用无障碍服务。通知、数据库、网络、闹钟全是 Android 自带的 API。

采集范围在设置里自己勾，默认开的是主流聊天、校园和短信类。常驻通知（播放器、下载进度）一律不采。

## 申请 DeepSeek API Key

1. 打开 https://platform.deepseek.com/ 注册，手机号或邮箱都行
2. 登录后左侧菜单找「API keys」
3. 点「创建 API key」，起个名字（`campus-todo` 就行），**把生成的 key 立刻复制走**
   - 只显示这一次，关掉页面就再也看不到了
4. 去「充值」页面充点钱。不充值是调不通的，余额为 0 会直接返回 402
5. 打开 App → 设置 → DeepSeek API Key → 粘贴 → 保存

费用按 token 算。这个 App 一次整理发十条通知，一天几十次调用花不了几毛钱。

里面也做了封顶：每天 60 次调用、30 万输入 token、6 万输出 token，撞到就停，第二天自己重置。

## API Key 存在哪

存在手机本地的 Android Keystore 里，AES-256-GCM 加密，不上传云端，也不同步。

卸载重装或清除应用数据，Keystore 里的密钥跟着没了，Key 得重填。这是设计如此。

## 现状

半成品。

没有经过准确的使用测试和验证。功能是通的，边界也算清楚，但"装到别人手机上能不能稳定跑一个月"这件事没验过。

仓库里那 90 个单元测试覆盖的是纯逻辑部分——时间解析、去噪规则、BLE 帧编解码。真机上的行为要靠你自己试。

## 关于 AI Passport 端

`firmware` 目录是当初给 ESP32 端留的位置，协议和结构写过一阵，没做完，也没在真实硬件上验证过。可以先当它不存在。

## 安装

从 [Releases](https://github.com/YuMeguru/campus-todo/releases) 下 `app-debug.apk`，直接装。

装完有两件事必须做，不做等于白装：

1. 系统设置里给这个 App 开"通知使用权"
2. 电池策略设成"不限制"（ColorOS 尤其要设，不然后台被杀，定时整理跑不动）

包名 `dev.campus.todo`，minSdk 31，targetSdk 36。

## 拿得到什么，拿不到什么

拿得到：通知里出现过的文字。

拿不到：

- 没弹通知的消息。静音的群、开了"隐藏通知内容"的会话，系统压根不递过来
- 装 App 之前的历史消息。通知监听没有回溯能力
- 被系统杀掉后台那段时间的消息
- 图片、语音、文件的内容，只有标题文字

这些是平台边界，不是没写完。界面里也照实标了。

## 构建

```powershell
$env:JAVA_HOME       = 'D:\Android\jdk'
$env:ANDROID_HOME    = 'D:\Android\sdk'
$env:GRADLE_USER_HOME= 'D:\Android\gradle'
$env:PATH            = "$env:JAVA_HOME\bin;$env:PATH"

cd android
& 'D:\Android\gradle-dist\gradle-8.14.3\bin\gradle.bat' assembleDebug testDebugUnitTest
```

产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。

环境是用 `android-setup.ps1` 铺的，走腾讯和清华的镜像（这台机器上 `dl.google.com` 和 `github.com` 都不可达）。脚本幂等，重装跑一次就行。

## 许可

MIT，见 [LICENSE](LICENSE)。
