# MoreTalk 适老增强开发计划（2026）

> 基于 [su-Insight/moretalk](https://github.com/su-Insight/moretalk)（v1.3.2，master 分支）的二次开发计划。
> 目标：在"已多机型验证可用"的基础上，为高龄老人增加 5 项全局覆盖能力，并以 GitHub fork 方式迭代维护。

---

## 1. 现状盘点

### 1.1 代码结构（单模块 Android App）

| 模块 | 位置 | 说明 |
|---|---|---|
| 桌面主界面 | `presentation/activity/MainActivity.kt`（1791 行） | 极简桌面：日期/天气卡片、联系人卡片、常用应用网格，兼 HOME launcher |
| 微信自动化 | `com/google/android/accessibility/selecttospeak/WechatAccessibilityService.kt`（37.6KB） | 7 步微信视频/语音拨号自动化，已具备 `canPerformGestures` + 坐标点击（`performClick(x,y)`） |
| 语音层 | `service/BundledSpeechEngine.kt` + 系统 TTS（`MainActivity.speakText`） | 自动/系统/内置 Matcha 三模式，全局播报音量 |
| 设置页 | `presentation/activity/SettingsActivity.kt`（520 行） | SharedPreferences + Switch 风格设置 |
| 工具 | `utils/`（CryptoUtil、DelayHelper、Logger 等） | 数据加密、步骤延迟等 |

### 1.2 技术基线
- Kotlin 1.9 / AGP 9.0.0 / minSdk 24（Android 7.0）/ targetSdk 36 / versionName 1.3.2
- 无障碍服务配置：`typeWindowStateChanged|typeNotificationStateChanged`，`canPerformGestures=true`
- 已声明权限：`CAMERA`（手电筒可用）、`SYSTEM_ALERT_WINDOW`（悬浮窗可用）、`CALL_PHONE`、`INTERNET`、定位
- MainActivity 已有悬浮窗权限申请/检测流程（`requestOverlayPermission`），可直接复用

### 1.3 已知问题（fork 后优先处理）
- ⚠️ Manifest 引用了不存在的 `presentation/activity/WeChatActivity`（上游遗留，本地同样存在），需在首次构建验证时确认是否阻塞编译，若阻塞则按"删除冗余声明"处理（相关逻辑实际由无障碍服务完成）。
- 本地目录未初始化 git；`third_party/sherpa-onnx/*.aar`（约 40MB 级）入库前需确认是否纳入版本控制（建议 LFS 或保持现 `.gitignore` 策略外的直接提交，二选一，见 §5.3）。

---

## 2. 五项功能技术方案

> 每项均给出：实现路径 / 关键 API / 复用资产 / 风险与对策。按实现难度排序（低→高）。

### 2.1 开关手电筒 —— 难度 ★☆☆☆☆

**路径**：首页新增超大"手电筒"按钮（开/关两态），使用 `CameraManager.setTorchMode(cameraId, on)`。
**关键点**：
- `CAMERA` 权限已声明，运行时授权一次即可；无相机设备时按钮置灰。
- 通过 `CameraManager.TORCH_CALLBACK` 监听真实状态，避免状态不同步。
- 手机壳/折叠屏多摄像头机型：取第一个 `cameraId`（前置闪光灯机型兜底判断）。
- 可选增强：音量键/悬浮球联动开关（后续）。
**风险**：个别 ROM（如部分华为机型）限制第三方 torch 调用 → 需机型矩阵验证，失败时提示引导系统控制中心。

### 2.2 一键返回界面（悬浮球回桌面） —— 难度 ★★☆☆☆

**路径**：新增悬浮球服务（`SYSTEM_ALERT_WINDOW` 已授权 + 申请流程已存在），点击回到 MoreTalk 桌面；首页也加一个"返回桌面"大按钮。
**关键点**：
- 悬浮球：大尺寸半透明球体贴屏幕边缘，长按拖动位置，单击回桌面（`Intent(ACTION_MAIN) + CATEGORY_HOME` 拉起 MainActivity）；双击或长按可选"回上一页"（无障碍 `performGlobalAction(GLOBAL_ACTION_BACK)`）。
- 设置页新增开关"桌面悬浮球"，老人可家属代开。
- 与既有"返回键循环点击"修复（`shouldConsumeBackPress`）不冲突，需回归测试。
- 悬浮球样式沿用超大字体/高对比设计规范。
**风险**：悬浮窗在部分 ROM 需额外"后台弹出界面"权限（MIUI 等）→ 引导流程已存在于 MainActivity，复用即可。

### 2.3 骚扰电话拦截 —— 难度 ★★★★☆（兼容性难点）

**路径**：`CallScreeningService`（API 24+，正好等于 minSdk），`onScreenCall` 中按规则 `respondToCall`（拒接/静音/不显示）。
**拦截规则（设置页可配）**：
- 白名单放行：通讯录联系人、收藏联系人（需 `READ_CONTACTS`）
- 黑名单：手动添加号码（从通话记录导入可选，需 `READ_CALL_LOG`）
- 号段规则：未知号码 / 隐藏号码 / 指定前缀（400、95、106 等可编辑）
**UI**：设置页新增"骚扰拦截"区块 + 独立黑名单管理页。
**兼容性对策（本项目最大风险点）**：
- MIUI/ColorOS/OriginOS/鸿蒙等 ROM 对第三方 CallScreeningService 支持参差（可能不回调或仅静默）→ 必须真机矩阵验证；
- 兜底设计：拦截失败时降级为"来电语音播报 + 拒接引导"，并提示家属开启系统自带的骚扰拦截；
- 注意 Android 14+ 对 CallScreeningService 仍有回调，但需在 `onScreenCall` 内尽快响应。
**备选方案**（若 ROM 全面不兼容）：仅做"来电识别播报"（READ_PHONE_STATE + 通知）而非真正拦截，作为降级开关。

### 2.4 微信消息语音点读 —— 难度 ★★★☆☆

**路径**：无障碍服务监听微信聊天页内容变化（需在 `accessibility_service_config.xml` 增加 `typeWindowContentChanged`），提取新增消息文本 → 去重 → 走现有 `speakText` 语音层播报。
**两种模式**：
1. **自动播报**：开关开启后，收到新消息自动读出"XXX 说：内容"（可配置只读昵称+内容/仅提示有新消息）。
2. **手动点读**：首页大按钮"读微信"→ 自动打开微信并朗读最近未读消息（复用现有 7 步导航的查找/点击能力）。
**关键点**：
- 去重：记录已播报节点（viewId/消息时间戳指纹），避免重复朗读；忽略自己发出的消息。
- 文本清洗：去掉表情、链接、@、引用块；数字中文化（复用 `normalizeNameForSpeech` 等现有逻辑）。
- 隐私：全部本地处理，不联网，符合项目"拒绝云端"理念；默认关闭，需家属开启。
- 兼容：微信 UI 树随版本变化 → 用 viewId+文本特征组合匹配 + 失败降级（不播报/提示）。
**风险**：误读、刷屏限流 → 播报间隔节流（复用 `DelayHelper`）。

### 2.5 远程协助 —— 难度 ★★★★★（分期实施）

**目标**：家属远程看到老人手机屏幕并（后续）远程操作，配套语音沟通。

**方案对比**：

| 方案 | 说明 | 结论 |
|---|---|---|
| A. scrcpy（局域网 adb） | `tools/scrcpy-win64-v3.1` 已在本地；PC 端投屏+控制 | 仅作开发调试工具，非老人可用产品形态 |
| B. 应用内屏幕共享+远程控制（推荐） | MediaProjection 录屏 → WebRTC/H.264 推流 → 信令服务器中转；远程端浏览器观看；控制走无障碍 `dispatchGesture` 注入 | 自研可控、符合本地化理念，分三期落地 |
| C. 第三方 SDK（向日葵/TeamViewer 等） | 成熟但商业授权、体积大、品牌外露 | 不推荐为主方案，可作兜底 |

**方案 B 分期**：
- **P1 屏幕共享 + 语音**：老人手机 MediaProjection 录屏推流（家属端 Web 页面输入房间码观看）；语音借用微信视频通话或 WebRTC 音频；老人端"一键发起协助"大按钮生成房间码。
- **P2 远程控制**：家属端点击坐标 → 信令下发 → 老人端无障碍服务 `dispatchGesture` 执行点击/滑动（复用现有 `performClick(x,y)` 与手势能力）；需二次确认防误操作（家属点击时老人端显示高亮框）。
- **P3 增强**：涂鸦标注、远程截屏留档、连接中状态提示（老人侧大字号"正在协助中"）。

**关键风险**：
- MediaProjection 每次连接需系统确认弹窗 → 老人场景由家属电话引导，或开发"常驻授权"缓解（ROM 差异大）；
- 部分 ROM 对录屏内容黑帧（DRM/省电策略）→ 机型矩阵验证；
- 信令服务器需自建（Node/WebSocket 或轻量 MQTT），仓库内提供 `server/` 目录与部署文档；房间码 6 位数字，老人可口头念给家属。
- 后台保活：复用现有自启动/电池优化豁免引导。

---

## 3. 里程碑规划

> 排序原则：低风险先落地 → 高价值次之 → 高风险最后；每阶段结束均可出包验证。

| 里程碑 | 内容 | 验收标准 | 预计工作量 |
|---|---|---|---|
| **M0 基建** | git 初始化、fork 关联、首次构建验证（处理 WeChatActivity 遗留）、CI（可选） | `./gradlew assembleDebug` 通过，APK 可装 | 0.5 天 |
| **M1 速赢** | 手电筒开关 + 一键返回桌面（悬浮球+首页按钮） | 真机 3 台（MIUI/ColorOS/原生）验证 | 1–2 天 |
| **M2 语音点读** | 微信消息自动播报 + 手动点读 | 微信最新版真机验证 3 机型 | 2–3 天 |
| **M3 骚扰拦截** | CallScreeningService + 规则配置 + 黑名单页 | 机型矩阵验证 + 降级路径可用 | 3–4 天 |
| **M4 远程协助** | P1 屏幕共享 → P2 远程控制 → P3 增强 | 局域网+公网信令各验证一轮 | 2–4 周（分期交付） |

> **M1 状态（2026-09-05）**：✅ 已实现并完成首次真机验证（realme RMX3700 / Android 13）。
> - 手电筒：独立「手电筒」桌面图标（LAUNCHER 入口 + 透明主题），点击即开关并自动返回；同时支持加入「常用应用」网格；`CameraManager.TorchCallback` 同步真实状态，无闪光灯机型自动提示。
> - 一键返回：悬浮球固定在屏幕右边缘居中偏上（不可拖动，避免误拖丢失），单击回 MoreTalk 主界面；设置页新增「桌面悬浮球」开关（默认开启）。
> - 常用应用图标放大至 76dp（与悬浮球同尺寸），首页布局保持原样。
> - 待用户真人验收：悬浮球从其他应用返回、手电筒图标/常用应用入口行为、设置页开关与权限引导流程。

> **M2 状态（2026-09-05）**：🔄 代码完成，待真机验收（realme RMX3700）。
> - 「微信点读」开关瓦片：固定显示在常用应用网格末尾，绿色=开/灰色=关，点击即切换（已实测双向切换、pref 同步）。
> - 开启后：微信聊天页点按消息即朗读该消息内容（`TYPE_VIEW_CLICKED`，父链回溯解析"发送者 说：内容"）；新消息到达自动播报（聊天页内容变化检测 + 通知兜底，去重防重复）。
> - 播报不干扰微信拨号自动化（`WeChatData.index != 0` 时跳过）；无障碍事件配置含 `typeWindowContentChanged|typeViewClicked`。
> - 设置页「微信消息点读」开关与瓦片联动（同一开关）。
> - 验收步骤（用户）：系统设置开启 MoreTalk 无障碍服务 → 点首页「微信点读」瓦片到绿色（开）→ 进入微信点按消息听朗读 / 让家属发消息听自动播报。

> **M4 状态（2026-09-05）**：🔄 P1/P2 局域网版已打通并真机验证（realme RMX3700）。
> - 方案：MediaProjection 录屏 → MJPEG 流（NanoHTTPD 服务 :8890）→ 家属浏览器打开 `http://手机IP:8890` 实时观看；点击画面 → 无障碍 `dispatchGesture` 注入实现远程控制。
> - ✅ 已实测：/status 200、控制页 200、MJPEG 帧流（626ms/110KB）、**远程点击翻转开关**（PC→手机全链路）。
> - 已知限制：仅局域网直连；手机开代理节点（VPN）时局域网入站被劫持需临时关闭；重装后无障碍服务需重开。
> - 待做：跨网访问（UPnP 端口映射 / WebRTC P2P / 公网中转信令）、帧率与画质调优、语音通道。

## 4. 分支与迭代策略

```
master        ← 与上游 su-Insight/moretalk 保持同步（仅上游变更）
feat/flashlight      feat/home-button       feat/wechat-read
feat/call-block      feat/remote-assist
dev           ← 集成分支（各 feat 合入后统一构建验证）
```

- 每个功能一个 feature 分支，完成+真机验证后合入 `dev`，稳定后发 tag 出包。
- 上游更新时：`git fetch upstream && git merge upstream/master` 到 master，再 rebase dev。
- 每次改动沿用项目 `UPDATE_LOG.md` 更新习惯。
- 提交规范：沿用上游中文提交风格（`feat:` / `fix:` / `docs:` 前缀）。

## 5. 工程化待办

### 5.1 首次构建验证
- 本地跑 `./gradlew assembleDebug`，确认上游 master 在 AGP 9.0.0 下可编译；若 WeChatActivity 缺失导致构建失败，在 M0 修复（移除多余 manifest 声明或补空 Activity）。

### 5.2 机型兼容矩阵
| 品牌/系统 | 版本 | 手电筒 | 悬浮球 | 骚扰拦截 | 微信点读 | 远程共享 |
|---|---|---|---|---|---|---|
| 小米 MIUI/HyperOS | 14/15 | ✅ | ✅ | ⚠️ | ✅ | ⚠️ |
| OPPO/一加 ColorOS | 14+ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ |
| vivo OriginOS | 4+ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ |
| 华为/荣耀 鸿蒙 | 4+ | ⚠️ | ⚠️ | ⚠️ | ✅ | ⚠️ |
| 原生/三星 | 14+ | ✅ | ✅ | ✅ | ✅ | ✅ |

（⚠️ = 需专项验证，测试后更新结论）

### 5.3 仓库体积与 AAR
- `third_party/sherpa-onnx/*.aar` 约 40MB：建议保持普通文件入库（项目体量尚可）；若仓库膨胀，再迁移 Git LFS。

### 5.4 安全与隐私
- 沿用"拒绝云端"原则：拦截规则、播报、远程协助信令均为本地+自建服务器，不上传任何个人数据；
- 远程协助连接必须房间码 + 老人端可见授权，缺一不可。

---

## 6. 待确认决策（开工前）

1. **远程协助信令服务器**：自建 Node/WebSocket（仓库内置 `server/`）还是先 P1 仅局域网验证？→ 建议先局域网，再上公网。
2. **骚扰拦截强度**：默认"仅拦截未知号码+黑名单"（保守，防误拦），号段规则默认关闭？→ 建议保守默认。
3. **微信点读默认开关**：默认关闭、家属手动开启（隐私优先）→ 建议默认关闭。
4. **fork 归属**：fork 到当前 GitHub 账号（待确认用户名）后，本地 `origin` 指向 fork、`upstream` 指向原仓库。

> 计划将随 fork 提交入库，后续每次迭代在 `PLAN.md` 勾选进度。
