# WirelessAdb

**LSPosed 模块**：开机首次解锁后自动开启无线 ADB，并在状态页查看地址、切换模式、手动复制、检查更新。

- 包名：`dev.wirelessadb.autostart`
- 当前版本：**1.2.31**
- 目标环境：一加 15 / ColorOS 17（Android 17）＋ LSPosed ≥ 2.1.0（libxposed API 102）
- 许可：MIT，基于 [shirenran/WirelessAdb-LSPosed](https://github.com/shirenran/WirelessAdb-LSPosed) 修改

<p align="center">
  <img src="docs/screenshot.png" alt="无线 ADB 自启状态页" width="360" />
</p>

## 功能

1. **自动开启并保持**：开机首次解锁、无线调试被系统关闭、Wi-Fi 断开重连、亮屏，都会检查并补齐无线 ADB。
2. **双模式**：TLS（系统「无线调试」，端口随机，需配对）与 TCP（等价 `adb tcpip <port>`，默认 5555，电脑可直接 `adb connect IP:5555`）。
3. **总开关**（状态卡右上圆形按钮）：一键决定模块是否接管无线 ADB，状态跨重启保持。
4. **地址复制**：点按状态页地址即写入系统剪贴板；地址变化时也会自动复制。
5. **应用内更新**：底部「更新日志」「检查更新」，发现新版本可就地下载安装，弹窗直接展示本次更新内容。
6. **运行记录**：开机早期与运行期日志写入 `Settings.Global`，状态页可回看，用于排障。

### 总开关的行为边界

开关存 `Settings.Global` 的 `wirelessadb_autostart_enabled`（1/0），hook 在 `system_server` 内、开机首次解锁前即可读，因此重启、断电、模块重载后都严格遵循关机前的状态。

| 场景 | 开关「开」 | 开关「关」 |
|---|---|---|
| 开机首次解锁 | 自动开启并保持 | 不开启，等用户手动打开 |
| 无线调试被系统关闭 | 自动重新开启 | 不介入 |
| Wi-Fi 断开后恢复 | 检查并补齐 | 不介入 |
| 亮屏 | 检查并补齐 | 不介入 |
| 切换 TLS/TCP | 立即按新模式重启 adbd | 只保存配置，不重启 adbd |
| 点地址复制 | 写入剪贴板 | 提示「无线 ADB 已关闭」 |

关闭时执行的落地动作：TLS 写 `adb_wifi_enabled=0`；TCP 置 `service.adb.tcp.port=-1` 并重启 adbd。**只关网络侧入口，不影响 USB 调试**。

> ⚠️ 关闭后无法再用 adb 连入，只能在本机点该开关重新打开。若手机不在手边，请勿关闭。

### 应用内更新

- 点「检查更新」手动查询；App 启动时也会静默检查（上下午各一次，无新版不打扰）。
- 下载走镜像加速：`ghfast.top` → `gh-proxy.com` → `ghproxy.net` → 直连，逐个尝试。
- GitHub API 被限流（国内常见 403）时自动降级：用 `releases/latest` 的 302 跳转解析 tag，再按仓库命名规则拼直链；更新说明改从 `releases.atom` 取。
- 更新弹窗直接展示该版本的更新内容；跨多个版本升级时只显示最新一版，不拼接历史。
- 同版本号重新出包（APK SHA-256 不同）也能被识别为可更新。
- 安装完成后自动清理缓存里的更新包（仅当缓存包与已安装包的 sha256 一致才删，避免误删）。

### 剪贴板与输入法

**不必须安装微信输入法**。复制写的是 Android 系统剪贴板，豆包、搜狗、百度、Gboard 等任何输入法都能直接粘贴。

日志里「微信输入法已运行，3 秒后触发跨设备同步」只针对**微信输入法的跨设备粘贴**：它的同步服务启动较慢，所以在运行时会延迟 3 秒再写一次剪贴板，确保变更能被其跨设备通道捕获。这 3 秒是给微信输入法留的窗口，不是系统限制。

### 状态页交互

- 点**地址文字**＝立即复制；
- 点右上**闪电**＝总开关（开/关无线 ADB）；
- 点 **TLS / TCP** 按钮＝切换模式（TCP 可改端口，需 1–65535）；重复点当前模式不做改动，只提示「已是当前模式」；
- 「运行记录」标题右侧刷新图标＝刷新状态与日志。

切换模式会重启 adbd，当前 ADB 会话必然断开，需用新地址重连；TLS 端口每次重启都会重新随机，电脑端可用 `adb mdns services` 自动发现。

## 环境要求

- **LSPosed ≥ 2.1.0**（提供 libxposed API 102、兼容 Android 17）。低于该版本不提供 API 102，本模块不会加载。
- 已 root（KernelSU / Zygisk Next 等）且 LSPosed 能注入 system_server。
- 目标机型：一加 15 / ColorOS 17（Android 17）；其它 Android 11+ 设备理论上同样可用。

> API 102 模块与 legacy API 82 模块在 classloader 层互斥。本版本**不能**与旧版（1.0.18）共存，升级时先卸载旧版。

## 安装

1. 安装 Release APK（若装过 1.0.18 且提示签名冲突，先卸载旧版）。
2. 在 **LSPosed** 中启用本模块。
3. 作用域确认为 **系统框架 / system**（`staticScope=false`，如管理器里未勾选可手动加上；升级/卸载重装后启用状态与作用域会被清空，需重新勾选）。
4. 重启手机并完成首次解锁。
5. 打开「无线ADB」状态页，看到 `[框架] 模块已加载` 即代表 hook 生效。

> 仅建议在可信局域网使用。

## ColorOS 17 / Android 17 必查项

即使模块已加载，下列系统策略仍可能让无线 ADB 被关闭：

1. **开发者选项 → 关闭「权限监控」/「禁止系统优化」**（ColorOS 对 ADB 的已知限制）。
2. **无线调试 → 把当前 Wi-Fi 标记为「始终允许」**。ADB Wi-Fi 2.0 以 SSID+BSSID 判定可信网络，陌生/公共 Wi-Fi 会自动关闭无线调试。
3. 确认 **USB 调试** 已开启；TLS 模式下电脑端用 `platform-tools ≥ 37.0.0` 的 `adb pair` / `adb connect`。
4. 状态页若出现 `[警告] 60 秒内连续 3 次被系统回退`，说明是策略性关闭，模块已停止写回；按上面两项处理后再点一次当前模式按钮。

## 排障

| 现象 | 检查 |
|---|---|
| 状态页显示「模块未启用」 | LSPosed 版本是否 ≥ 2.1.0；模块是否启用；作用域是否含系统框架；是否被隐藏/root 检测模块干扰 |
| 有 `[框架] 模块已加载` 但无 `[框架] 已取得系统 Context` | 查看 `[警告]` 里的候选字段与 LSPosed 日志，反馈给维护者 |
| `获取地址失败…端口=-1` | 看 `[诊断] TLS 端口来源探测`：若 `AdbService` 与 `dumpsys` 均为 -1，说明该 ROM 改了 AIDL 或禁止 system_server 执行 dumpsys，把该行回传 |
| `adb_wifi_enabled` 写入后回读不为 1 | ColorOS 忽略写入，走可信网络 + 权限监控两项设置 |
| TCP 5555 连不上 | 看 `[诊断]` 里 `tcp.port` 回读值与 `adbd` 状态；Android 17 重写了 adbd，必要时改用 TLS 模式 |
| 检查更新提示 HTTP 403 | 手机出口 IP 被 GitHub 限流，模块会自动走降级链路；仍失败看运行记录里的 `update check` 行 |

LSPosed 的详细日志 tag 为 `WirelessAdbAutoStart`，也可直接读 `/data/adb/lspd/log/modules_*.log`。

## 构建

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

需要 Android SDK（`local.properties` 的 `sdk.dir` 或 `ANDROID_HOME`）与 JDK 17+。当前 `compileSdk/targetSdk = 36`：framework 侧全部走反射，不依赖编译期 Android 17 API，无需 SDK platform 37 即可构建。

验证 APK 声明是否打进包：

```bash
unzip -l app/build/outputs/apk/release/app-release.apk | grep META-INF/xposed
```

应看到 `module.prop`、`java_init.list`、`scope.list` 三个文件，且不应再出现 `assets/xposed_init` 与 `xposedminversion`。

## 更新记录

完整版本变更见 [Releases](https://github.com/88558639/WirelessAdb/releases) 与 App 内「更新日志」按钮，README 不再逐版罗列。

## 致谢

本项目源自 [shirenran/WirelessAdb-LSPosed](https://github.com/shirenran/WirelessAdb-LSPosed)（原作者 shirenran，MIT 协议，最初版本 1.0.18）。本 fork 在其基础上适配 ColorOS 17 / Android 17 并持续维护：

- 迁移到 **libxposed API 102**，可直接注入 `system_server`；
- 修复 Android 17 上 TLS 端口读取失败（属性 → `AdbService.getAdbWirelessPort()` → `dumpsys adb` 三级探测）；
- 修复无谓重启 adbd 导致的无线 ADB 断连；模块加载状态改用独立标记 + `boot_count` 锚定；
- 状态页改版（单页 Dashboard、总开关、文案中文化）、桌面图标改版、应用内更新。

感谢原作者 shirenran 的开源贡献。上游仍存在的问题欢迎在原仓库反馈。

## 许可

MIT

原作 `Copyright (c) 2026 shirenran` 的版权声明完整保留于 [LICENSE](LICENSE)。本 fork 的改动部分以 MIT 协议发布。
