# AI 助手 · UI 视觉与交互规格

> 适用范围：KernelSU Manager（`manager/app/src/main`，Jetpack Compose，Material3 + Miuix 双主题）。
> 本文是**设计规格**：页面骨架、间距 / 圆角 / 字号、交互组件、安全闸门分级、AI 访问范围三档。
> 路径约定：不带前缀的源码路径均相对于 `manager/app/src/main/java/me/weishu/kernelsu`，资源路径相对于 `manager/app/src/main/res`。
> 文中每条断言都以仓库现有代码为准，并给出源码文件或符号名，可用文件名 / 符号名检索核对。

---

## 0. 交付物与边界

| 交付物 | 路径 | 状态 |
| --- | --- | --- |
| 设计规格（本文） | `design/ai-console-spec.md` | 本文件 |
| 模型与参数配置页 | `ui/screen/aiconfig/` | 已实现 |
| 提供商 / 模型列表 / 访问范围 Sheet、升权弹窗 | `ui/component/{miuix,material}/AiProviderSheet.kt`、`AiModelListSheet.kt`、`AiScopeSheet.kt`、`AiScopeElevationDialog.kt` | 已实现 |
| 访问范围与审计数据层 | `data/agent/AiAccessScope.kt`、`data/agent/AiAuditEntry.kt`、`data/repository/AiAuditRepositoryImpl.kt` | 已实现 |
| AI 控制台（对话 + [AI_AGENT] 执行日志） | `ui/screen/aiassistant/`、`ui/component/aichat/` | 已实现 |
| AI 智能权限审查页 | `ui/screen/aipermission/` | 已实现 |
| 模块冲突检测页 | `ui/screen/aiconflict/` | 已实现 |
| AI 操作历史与撤销页 | `ui/screen/aiaudit/` | 已实现 |
| 模块制作页 | `ui/screen/modulemaker/` | 已实现 |

**不在本文范围**：模型调用协议与请求体构造（见 `data/remote/AiChatClient.kt`）；网络层重试与超时策略（见 `data/remote/AiHttpClient.kt`）。

---

## 1. 设计基线与复用组件

### 1.1 网格与间距

| 项 | Miuix 侧 | Material 侧 | 来源 |
| --- | --- | --- | --- |
| 页面左右边距 | 12dp | 16dp | `ui/screen/home/HomeDimens.kt`（PageHorizontal = 12.dp）；`ui/screen/home/HomeMaterial.kt` |
| 页面顶距 | 12dp | 13dp | `ui/screen/home/HomeDimens.kt`（PageTop）；`ui/screen/aiconfig/AiConfigMaterial.kt` |
| 卡片垂直间距 | 12dp | 13dp | `ui/screen/home/HomeDimens.kt`（CardSpacing）；`ui/screen/home/HomeMaterial.kt` |
| 卡片内边距 | 16dp | 16dp | `ui/screen/home/HomeDimens.kt`（CardInner）；`ui/screen/home/HomeMaterial.kt` |
| 配置卡内边距 | 16dp | SegmentedColumn 自带宽距 | `ui/screen/aiconfig/AiConfigMiuix.kt`（CardContentPadding = 16.dp） |
| Sheet 行内边距 | 20dp / 16dp | 20dp / 16dp | `ui/component/{miuix,material}/AiModelListSheet.kt`（SheetRowPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)） |
| Sheet 内容左右 | 20dp | 20dp | 同文件（Modifier.padding(horizontal = 20.dp, vertical = 24.dp)） |
| 列表最大高度 | 460dp | 460dp | 同文件（SheetListMaxHeight = 460.dp） |

**侧边距口径（重要）**：早期约定要求「侧边距统一 16dp」，仓库现状则是 **Miuix 页面 12dp / Material 页面 16dp** 两条既有对齐轴。本规格按「无痕融入优先」处理：**跟随所在页面的模式惯例**（Miuix 12dp、Material 16dp），不引入第三种边距，也不为 AI 页面单独把 Miuix 改成 16dp（那会让 AI 配置页与同模式的设置页 / 主页错位 4dp）。偏差已登记于 §9。

### 1.2 圆角

| 项 | 规格 | 来源 |
| --- | --- | --- |
| Material 弹窗 | MaterialTheme.shapes.extraLarge | `ui/component/material/ExpressiveDialog.kt`（shape 默认 extraLarge） |
| Material BottomSheet | 系统 ModalBottomSheet 默认顶部大圆角 | `ui/component/material/AiProviderSheet.kt`（ModalBottomSheet） |
| Miuix 弹窗 / Sheet | OverlayDialog / OverlayBottomSheet 默认圆角 | `ui/component/miuix/AiModelListSheet.kt`（OverlayBottomSheet） |
| Miuix 卡片 | Card 默认圆角 | `ui/screen/aiconfig/AiConfigMiuix.kt` |

「大卡片与弹窗圆角统一 16–24dp」由上述默认值满足，**不新增自定义 shape**。

### 1.3 字体标度（双模式映射）

| 语义 | Miuix | Material |
| --- | --- | --- |
| 页面标题（TopAppBar） | Miuix 默认 TopAppBar 标题 | MaterialTheme.typography（LargeFlexibleTopAppBar） |
| 卡片 / 弹窗标题 | MiuixTheme.textStyles.title4（Medium） | MaterialTheme.typography.titleLarge（Bold） |
| 列表主文本 | MiuixTheme.textStyles.body1 | MaterialTheme.typography.bodyLarge |
| 次级说明 | MiuixTheme.textStyles.body2 | MaterialTheme.typography.bodyMedium |
| 分组小标题 | MiuixTheme.textStyles.footnote1（Medium + onSurfaceVariantSummary） | MaterialTheme.typography.labelMedium |

来源：`ui/component/miuix/AiProviderSheet.kt` 的 body1 用法；`ui/component/miuix/SendLogDialog.kt` 的 title4 用法；`ui/component/material/AiProviderSheet.kt`。

### 1.4 复用组件清单（禁止重造）

| 组件 | 路径 | 用途 |
| --- | --- | --- |
| OverlayBottomSheet | top.yukonga.miuix.kmp.advanced | Miuix 侧所有底部弹窗 |
| OverlayDialog / WindowDialog | top.yukonga.miuix.kmp.advanced / `ui/component/miuix` | Miuix 侧所有模态弹窗 |
| ModalBottomSheet / AlertDialog | androidx.compose.material3 | Material 侧所有底部弹窗 / 模态弹窗 |
| rememberConfirmDialog | `ui/component/dialog/Dialog.kt` | LIGHT 级轻量确认（showConfirm / awaitConfirm） |
| ExpressiveDialog / ExpressiveConfirmDialog | `ui/component/material/ExpressiveDialog.kt` | Material 侧 STRICT 级弹窗 |
| SegmentedColumn / SegmentedListItem / SegmentedTextField / SegmentedSwitchItem | `ui/component/material/SegmentedList.kt` | Material 侧配置分组 |
| ArrowPreference / SwitchPreference / BasicComponent | top.yukonga.miuix.kmp.preference | Miuix 侧配置行 |
| StatusTag | `ui/component/statustag/{StatusTag,StatusTagMiuix,StatusTagMaterial}.kt` | 风险标签（[建议禁用] 等）零新组件 |
| MarkdownContent / GithubMarkdown | `ui/component/markdown/` | AI 回复渲染 |
| AiProviderSheet | `ui/component/{miuix,material}/AiProviderSheet.kt` | 提供商选择（三组 11 家） |

---

## 2. 页面 / 组件清单

| # | 页面或组件 | 入口 / 路由 | 状态 |
| --- | --- | --- | --- |
| P1 | 主页 AI 入口卡 | `ui/screen/home/HomeMiuix.kt`（AiAssistantCard） / `HomeMaterial.kt` → Route.AiConfig | 已实现 |
| P2 | 模型与参数配置 | Route.AiConfig（`ui/navigation3/Routes.kt`） | 已实现 |
| P3 | 选择 API 提供商（Sheet） | P2 内 API 提供商行 → AiProviderSheet | 已实现 |
| P8 | 选择模型（Sheet）+ 访问范围（Sheet + 升权弹窗） | P2 内「获取模型列表」/「AI 访问范围」行 | 已实现 |
| P4 | AI 控制台（对话 + [AI_AGENT] 执行日志 + 双闸门 + 计划模式 + 文件附件） | Route.AiConsole(kickoff)；主页 AI 入口卡 → 配置页底部「打开 AI 控制台」 | 已实现 |
| P5 | AI 智能权限审查 | Route.AiPermissionReview，超级用户页入口 | 已实现 |
| P6 | 模块冲突检测 | Route.ModuleConflict，模块页入口 | 已实现 |
| P7 | AI 操作历史与撤销 | Route.AiAudit，设置页 / 控制台入口 | 已实现 |

> 页面编号沿用本文档，P8 与 P3 同为 Sheet 级组件，放在 P4 之前因其先交付。

---

## 3. 页面线框与规格

线框为骨架示意，宽度 60 字符，不代表真实像素。

### 3.1 P1 主页 AI 入口卡（已实现）

    +----------------------------------------------------------+
    |  接入 AI 助手                                      [机器人] |
    |  使用 AI 快速管理 root 权限与模块                          |
    +----------------------------------------------------------+

| 规格项 | 值 | 来源 |
| --- | --- | --- |
| 位置 | 「支持开发」卡**上方**、信息卡下方 | `ui/screen/home/HomeMiuix.kt`（AiAssistantCard 位于 InfoCard 之后、DonateCard 之前） |
| 卡片 | 复用 Card，左右边距 12dp、卡间距 12dp | `ui/screen/home/HomeDimens.kt`（PageHorizontal / CardSpacing） |
| 标题 | 粗体，单行 | `R.string.home_ai_assistant_title`（接入 AI 助手 / Connect AI assistant） |
| 副标题 | 次级色，单行 | `R.string.home_ai_assistant_summary`（使用 AI 快速管理 root 权限与模块 / Use AI to manage root grants and modules quickly） |
| 尾部 | 机器人图标（AiAssistantIcon） | `ui/icon/AiAssistantIcon.kt` |
| 点击 | push Route.AiConfig | `ui/screen/home/HomeScreen.kt`（onOpenAiConfig） |

### 3.2 P2 模型与参数配置（已实现）

    +----------------------------------------------------------+
    |  <-   模型与参数配置                                       |
    +----------------------------------------------------------+
    |  卡片 1 · API 设置                                          |
    |  API 提供商                    Deepseek 大模型        >     |
    |  API 端点                                                   |
    |  [ https://api.deepseek.com/v1/chat/completions         ]  |
    |  API 密钥                                                   |
    |  [ ******************************                    (眼) ] |
    |  模型名称                                                   |
    |  [ 例如：deepseek-chat、gpt-4...                          ] |
    |  [ 获取模型列表 ]  (整宽 TextButton / 尾部刷新图标)          |
    +----------------------------------------------------------+
    |  卡片 2 · 权限与执行安全                                    |
    |  快捷策略模板                                               |
    |  [ 严格只读 ]   [ 智能助手 ]   [ 极客模式 ]                  |
    |  选择模板后，仍可在下方单独添加例外                          |
    |  允许 AI 执行 Shell 命令                        (开关·关)    |
    |  执行高危命令前强制确认                          (开关·开·锁)  |
    |  AI 访问范围                禁止访问                  >     |
    +----------------------------------------------------------+
    |  请求失败（401、超时）的提示会显示在这里                     |
    +----------------------------------------------------------+
    |  当前配置下，单次对话最高约消耗 47 万 Token（闲时约 ¥0.10）  |
    +----------------------------------------------------------+

| 规格项 | 值 | 来源 / 备注 |
| --- | --- | --- |
| TopAppBar | Miuix BlurredBar + TopAppBar（左返回、中标题 ai_config_title）；Material LargeFlexibleTopAppBar | `ui/screen/aiconfig/AiConfigMiuix.kt`（Scaffold + BlurredBar） |
| 卡片左右边距 | Miuix 12dp / Material 16dp | 见 §1.1 口径；`ui/screen/aiconfig/AiConfigMaterial.kt` |
| API 提供商行 | Miuix ArrowPreference / Material SegmentedListItem，endActions 显示当前提供商名 + 下拉箭头 | 点击弹 P3 |
| API 端点 | 文本输入，KeyboardType.Uri，提示词为当前提供商默认端点 | 校验失败显示红字 ai_error_endpoint_scheme |
| API 密钥 | 文本输入，KeyboardType.Ascii（**普通键盘**），PasswordVisualTransformation 掩码 + 眼睛图标切明文 | 设计约定：密钥不使用密码键盘 |
| 模型名称 | 文本输入，KeyboardType.Ascii，提示词随提供商变化 | ai_model_name_hint_provider |
| 获取模型列表 | Miuix 整宽 TextButton（条内 top 8dp）/ Material 行尾 IconButton(Refresh)；endpoint 为空或不合法时 disabled | `ui/screen/aiconfig/AiConfigMiuix.kt`（padding(top = 8.dp)）；canFetchModels |
| 访问范围行 | ArrowPreference / SegmentedListItem，右侧显示当前档位名，点击弹 P8 的范围 Sheet | 第四档交互见 §6 |
| 安全开关 | 「允许 AI 执行 Shell 命令」默认关；「执行高危命令前强制确认」默认**开且置灰不可关**（安全底线）；「AI 访问范围」三档默认**禁止访问** | 见 §5、§6 |
| 错误槽 | 卡片下方一行次级文本，有错误时换成红色并把文案替换为错误详情 | ai_error_slot_hint |
| 参数默认值 | 最大轮次 **50**（选项 1/2/3/5/8/10/30/50/80，自定义 1–100，兜底由 AiRounds.sanitize 夹取）；单次读取上限 **128000 字符**（选项 2000/4000/8000/16000/32000/64000/128000/256000，自定义 500–256000）；全量读取阈值 **2048 KB**（选项 32/64/100/256/512/1024/2048，自定义 8–4096） | `ui/screen/aiconfig/AiRounds.kt`、`ui/screen/aiconfig/AiReadLimits.kt`；执行器的 readFileWindow 直接使用这两个设置 |
| 快捷策略模板 | 卡片 2 顶部一行三选一，位于两个开关**上方**；点击即写 allowShell 与访问范围档位（宽范围仍走升权强拦截弹窗）；下方一行小字脚注（ai_template_footnote） | `ui/screen/aiconfig/AiPolicyTemplate.kt`（matching(allowShell, scope)；三档 STRICT_READ_ONLY / SMART_ASSISTANT / GEEK）；AiConfigViewModel.applyTemplate；行组件 AiTemplateRowMiuix / AiTemplateRowMaterial |
| Token 成本预估 | 错误槽上方一行次级文本，随上方三个参数即时变化 | `ui/screen/aiconfig/AiTokenEstimate.kt`：worstCaseTokens = 全量 KB × 1024 / 4 + 轮次 × 2000（CHARS_PER_TOKEN = 4、ROUND_OVERHEAD_TOKENS = 2000）；价格 YUAN_PER_MILLION = 0.02（deepseek flash 闲时缓存命中）；文案 ai_token_estimate |

**硬性约定**：密钥明文存 SharedPreferences（`ai_settings`，见 `data/repository/AiSettingsRepositoryImpl.kt`）是当前已知缺口，已在数据层注释记录（不为此单独引入加密依赖）；本文不新增加密要求。

### 3.3 P3 选择 API 提供商（Sheet，已实现）

    +----------------------------------------------------------+
    |                      选择 API 提供商                        |
    |  [ 搜索提供商                                          ]   |
    |  推荐 / 核心                                                |
    |  Deepseek 大模型                              推荐   ✔     |
    |  OpenAI 兼容                                  推荐          |
    |  Anthropic 通用                               推荐          |
    |  OpenAI（GPT 系列）                           推荐          |
    |  本地 / 私有                                                |
    |  Ollama / LM Studio                                        |
    |  其他国内外                                                |
    |  智谱 / 通义 / 文心 / 星火 / Gemini ...                     |
    |                                             [  取消  ]     |
    +----------------------------------------------------------+

| 规格项 | 值 |
| --- | --- |
| 容器 | Miuix OverlayBottomSheet / Material ModalBottomSheet（顶部大圆角、半透明遮罩） |
| 标题 | ai_provider_sheet_title，居中 / 左对齐按模式惯例 |
| 搜索框 | 全宽圆角，leadingIcon = Search，useLabelAsPlaceholder = true |
| 分组 | 三分组：推荐 / 核心（带「推荐」徽标）、本地 / 私有、其他国内外 |
| 选中态 | 行尾 Check（Miuix）/ Check 图标 + labelMedium 徽标（Material） |
| 底部 | 右下「取消」文字按钮（android.R.string.cancel，primary 配色） |
| 数据源 | `ui/screen/aiconfig/AiProviderCatalog.kt`（11 家：deepseek、openai_compatible、anthropic、openai、ollama、lmstudio、zhipu、qwen、ernie、spark、gemini；DEFAULT_ID = deepseek；anthropic 家族 = ANTHROPIC，其余 OPENAI_COMPATIBLE） |

### 3.4 P8 选择模型 / 访问范围（Sheet + 升权弹窗，已实现）

    [ 选择模型 ]                              [ 选择访问范围 ]
    +-----------------------------+          +-----------------------------+
    |  (加载中：环形进度 160dp)    |          |  ( ) 禁止访问               |
    |  deepseek-chat          ✔   |          |  ( ) 允许访问 /data/adb/    |
    |  deepseek-reasoner          |          |  (o) 允许访问根目录 /       |
    |  ...（列表最高 460dp）       |          |                             |
    |  [重试]            [取消]    |          |              [取消]         |
    +-----------------------------+          +-----------------------------+

    [ 升权强拦截弹窗 ]（仅升档时）
    +----------------------------------------------------------+
    |                提升 AI 访问范围？                           |
    |  即将把 AI 的文件访问范围提升为「允许访问根目录 /」。        |
    |  升档后 AI 读取范围扩大，请确认你理解其风险。                 |
    |                       [ 取消 ]      [ 确认提升 ]            |
    +----------------------------------------------------------+

| 规格项 | 值 |
| --- | --- |
| 模型列表状态 | loading → InfiniteProgressIndicator（Miuix）/ CircularProgressIndicator（Material）；empty → ai_model_list_empty；error → body2 + colorScheme.error + 重试按钮 |
| 模型选中 | 回填「模型名称」输入框并关闭 Sheet；关闭后回包会被丢弃（不复活面板） |
| 范围 Sheet | 三行单选：label（body1）+ summary（footnote1，次级色），选中态 Check（Miuix）/ RadioButton（Material）；选项取自 `AiAccessScope.options`（NONE / DATA_ADB / ROOT_FS） |
| 升权弹窗 | 仅当 `AiAccessScope.isElevationFrom(current)` 为 true（目标档位 ordinal 更高）时出现；单击「确认提升」即生效，不再要求键入确认词（见 §4.2 与 §9 的 D-aa） |
| 降档 | 立即生效，不弹窗、不确认 |
| 无档位选择 | target 为 null 时直接 return，不渲染 |

### 3.5 P5 AI 智能权限审查（已实现）

入口：超级用户页顶栏 actions 图标（Miuix 用项目自有 `ui/icon/AiAssistantIcon.kt`，Material 用 `Icons.Filled.Security`），contentDescription = ai_permission_title。

数据源：`SuperUserRepositoryImpl().getAppList(): Result<Pair<List<AppInfo>, List<Int>>>`（与超级用户页同一个 root 服务），仅保留 `AppInfo.allowSu == true` 的条目。

分级规则（确定性、可复算、零联网；实现在 `ui/viewmodel/AiPermissionReviewViewModel.kt`，等级枚举见 `ui/screen/aipermission/AiPermissionUiState.kt`）：

| 级别 | 触发条件（满足任一） | 依据字段 |
| --- | --- | --- |
| HIGH | uid < SYSTEM_UID_FLOOR（10000，系统 / 特权应用）；capabilities 非空；groups 非空 | Natives.Profile |
| MEDIUM | rootUseDefault == false（自定义 root 配置）；rootTemplate != null；umountModules == false（不卸载模块，root 应用可看到模块与痕迹）；label 为空 | Natives.Profile |
| LOW | 其余（默认 root 配置且有应用名） | — |

    [ AI 权限审查 ]
    +--------------------------------------------------------------+
    |  4 apps hold root                              [刷新]         |
    |  文件访问范围：允许访问 /data/adb/            [控制台深入审查]  |
    +--------------------------------------------------------------+
    |  [高风险]  某工具            com.example.tool                  |
    |            系统级应用（uid 1000）                               |
    |                                          [ 撤销授权 ]          |
    +--------------------------------------------------------------+
    |  [需留意]  某模块应用         com.example.mod                   |
    |            该应用不卸载模块，可能看到 root 痕迹                  |
    |                                          [ 撤销授权 ]          |
    +--------------------------------------------------------------+

| 规格项 | 值 |
| --- | --- |
| 风险标签 | 沿用 `ui/component/statustag/` 的 StatusTag 形态（HIGH = error、MEDIUM = tertiaryContainer、LOW = secondaryContainer） |
| 撤销授权 | 走 LIGHT：共享 `rememberConfirmDialog`（`ui/component/dialog/Dialog.kt`）→ 确认后 `AiActionExecutorImpl.execute(AiAction.RevokeSu(pkg), scope)` |
| 撤销副作用 | executor 写审计（kind = su、before = allow、after = deny、undoable = true）⇒ P7 可一键恢复 |
| 系统应用护栏 | executor 自带（uid < 2000 且 ≠ 1000 拒绝，见 `data/agent/AiActionExecutor.kt`），失败原文直接显示在该行 |
| AI 深入审查 | 按钮 push `Route.AiConsole(kickoff = AiConsoleKickoff.ROOT_REVIEW)`（`ui/screen/aiassistant/AiConsoleKickoff.kt`），复用已实现的控制台（get_root_app_list JSON + 动作分级 + 审计） |
| 状态 | loading / empty（无授权）/ error（读取失败）/ 未配置（无 API 仍可看本地分级，AI 按钮置灰并提示先配置） |

---

### 3.6 P6 模块冲突检测（已实现）

入口：模块页顶栏 actions 图标（Miuix `MiuixIcons.HorizontalSplit`，Material `Icons.AutoMirrored.Filled.Rule`）。

数据源：`ModuleRepositoryImpl().getModules()`（ksud module list，判定 enabled 与名称）+ 逐模块读取 `module.prop`、system 目录下的文件清单与 `system.prop` 的键（全部只读）。实现在 `data/repository/ModuleConflictRepositoryImpl.kt`，等级与类型枚举见 `data/model/ModuleConflict.kt`。

| 级别 | 类型 | 判定 |
| --- | --- | --- |
| HIGH | 文件重叠（FILE_OVERLAP） | 同一个 system/ 相对路径出现在 ≥2 个启用模块 |
| HIGH | 模块 ID 重复（DUPLICATE_ID） | 两个目录的 module.prop id 相同 |
| MEDIUM | 属性重复（PROP_DUPLICATE） | system.prop 中同一键被 ≥2 个模块设置 |
| MEDIUM | 目录 ID 不一致 | 目录名 != module.prop id |
| MEDIUM | 缺少 module.prop | 目录内无 module.prop |

前置条件：访问范围 ≥ DATA_ADB（见 §6 闸门收口）；不足时只显示提示 + 「打开配置」，不发起扫描。

    [ 模块冲突检测 ]
    +--------------------------------------------------------------+
    |  已扫描 6 个启用模块                             [重新扫描]     |
    +--------------------------------------------------------------+
    |  [冲突] 文件重叠：system/etc/thermal.conf                      |
    |         涉及模块：burst_thermal, perf_boost                   |
    +--------------------------------------------------------------+
    |  [警告] 属性重复：debug.sf.hw                                  |
    |         涉及模块：mod_a, mod_b                                |
    +--------------------------------------------------------------+

| 规格项 | 值 |
| --- | --- |
| 扫描内容 | 每个启用模块的 module.prop、system 目录文件清单、system.prop 键（只读） |
| 计数口径 | 仅统计 enabled == true 的模块；禁用模块不计入冲突 |
| AI 解读 | 按钮 push `Route.AiConsole(kickoff = AiConsoleKickoff.MODULE_CONFLICT)`，由控制台按当前范围复算 |
| 状态 | 范围不足 / scanning / ok / empty（无冲突）/ error（扫描失败） |

---

### 3.7 P7 AI 操作历史与撤销（已实现）

入口：控制台顶栏 actions 图标（Miuix `MiuixIcons.Undo`，Material `Icons.AutoMirrored.Outlined.Article`）。

数据源：`AiAuditRepositoryImpl().read(limit = 200)` 之后过滤 `origin == assistant`（`filesDir/ai_audit.jsonl`，JSON Lines，Mutex 串行 append / trim / read），按 ts 倒序展示：这一页是 AI 的动作轨迹，不是用户设置的变更日志。

    [ AI 操作历史 ]
    +--------------------------------------------------------------+
    |  17:43:21  root 授权    com.example.tool         [成功][撤销]  |
    |  17:41:02  模块启停      hybrid_mount            [成功][撤销]  |
    |  17:39:44  只读命令      cat /proc/meminfo       [成功]        |
    +--------------------------------------------------------------+
    |  点行 → 行内展开：before / after / command / scope              |
    +--------------------------------------------------------------+

撤销映射（`data/agent/AiAuditUndo.kt`；撤销本身也写一条审计，避免无限链）：

| audit kind | before | 撤销动作 |
| --- | --- | --- |
| su | allow | `execute(AiAction.GrantSu(target))` |
| su | deny | `execute(AiAction.RevokeSu(target))` |
| module | enabled | `execute(AiAction.EnableModule(target))` |
| module | disabled | `execute(AiAction.DisableModule(target))` |
| move_to_trash | 原路径 | `mv <after> <before>`（回收站还原）后写审计 |
| SCOPE_CHANGE | 档位键名 | `settings.accessScope = AiAccessScope.fromKey(before)`（该行不再出现在本页；配置页的「恢复上一档」走同一读取路径 `AiAuditRepository.latest(SCOPE_CHANGE, user)`） |
| 其他 | — | 不支持，行内不出现撤销按钮 |

| 规格项 | 值 |
| --- | --- |
| 撤销闸门 | LIGHT：共享 rememberConfirmDialog，确认文案带 target |
| 撤销结果 | 追加审计 kind = undo、undoable = false；页面刷新并显示结果文案 |
| 详情 | **行内展开**（点击行 toggle，显示「Action detail」+ detail 文本）：单条详情只有两三行，弹底部弹窗多一次打断且要管理额外状态，故不做 Sheet。字段按 §1.3 二级字号 |
| 状态 | loading / empty（无记录）/ error（读取失败） |

---

### 3.8 P4 AI 控制台（已实现：计划模式 / 任务计划卡 / 文件附件）

    +----------------------------------------------------------+
    |  <-   AI 控制台                                            |
    |  deepseek-flash                                  [Clear]   |
    |  [AI_AGENT] 客户端日志                          展开 / 收起 |
    |  ██████ 深色日志区（收起 120dp / 展开 420dp，自动滚底）        |
    |  --------------------------------------------------------  |
    |  （用户）列出持有 root 的应用并测量 /data/adb 大小             |
    |  （助手）正文（可选 Reasoning 折叠卡）                        |
    |  +-- 📋 任务计划                          待批准 ---------+ |
    |  |  模型一句话摘要                                        | |
    |  |  共 3 步 · 只读动作批准后直接执行，写入仍会单独确认        | |
    |  |  1. Root grants        Automatic   [ Propose / Done ]  | |
    |  |  2. List directory     Automatic   [ Propose / Done ]  | |
    |  |  [ 修改计划 ]                    [ 一键执行全部 ]        | |
    |  +--------------------------------------------------------+ |
    |  +------------------------------------------------------+  |
    |  |  crash.log (483 B) ✕   second.log (39 B) ✕            |  |  ← 附件 chip 行（容器内、文字上方）
    |  |  描述你想让助手检查或执行的内容…                       |  |  ← 文字区独占整宽（44–148dp）
    |  |  📎 ┃ [ Chat ] [ Plan ]                        ( ↑ )    |  |  ← 动作行 48dp
    |  +------------------------------------------------------+  |
    +----------------------------------------------------------+

| 规格项 | 值 | 来源 |
| --- | --- | --- |
| 输入区（composer） | 单一 24dp 圆角容器：文字区在上、独占整宽（heightIn(min = 44.dp, max = 148.dp)，占位 ai_console_input_hint），动作行在下（48dp）：附件图标 ┃ 分隔线 ┃ Chat / Plan 胶囊 chip ┃ 发送按钮；容器内无第二层输入框、外层无描边 | `ui/screen/aiassistant/AiConsoleMiuix.kt`（BasicTextField + decorationBox、RoundedCornerShape(24.dp)）/ `AiConsoleMaterial.kt`（OutlinedTextField 透明色）；`ui/component/aichat/AiChatCommon.kt` 手绘图标 |
| 发送 / 停止 | 空闲：实心 primary 圆（40dp 圆套 48dp 热区，白色上箭头）；无输入且无附件时禁用（secondaryContainer）；发送中：primary 圆角方块停止按钮 | `ui/screen/aiassistant/AiConsoleMiuix.kt` / `AiConsoleMaterial.kt` |
| 附件入口 | 动作行最左手绘「托盘 + 上升箭头」图标（24dp，contentDescription ai_console_attach），点击唤起系统选择器 | `ui/icon/AiUploadFileIcon.kt` |
| 模式切换 | 位于 composer 动作行：两枚胶囊 chip，资源 ai_console_mode_chat（对话模式 / Chat）/ ai_console_mode_plan（计划模式 / Plan），选中填充 secondaryContainer、未选描边 outline；切换写日志「已切换到计划模式：先出计划，一键批准后统一执行」 | `ui/component/aichat/AiChatMiuix.kt`（AiModeSwitchMiuix）/ `AiChatMaterial.kt`；AiConsoleViewModel.setPlanMode |
| 计划模式行为 | 该轮模型提出的动作不逐个执行，先渲染任务计划卡（📋 ai_console_plan_title + 状态 chip + 摘要 + 每步复用动作卡）；点「一键执行全部」= 客户端按**该批**授权 | `ui/viewmodel/AiConsoleViewModel.kt`（runRounds 的 plan = AiPlanItem(...) 后 return，以及 approvePlan） |
| 批量执行的安全边界 | 计划模式自动放行只读动作（ReadFile / GetFileMetadata / ReadFileChunk / SearchInFile / ListDir / SafeExec / RootAppList）；写入、删除、grant_su / revoke_su、模块启停仍逐个弹原有确认；判定见 `autoApproved = decision.tier == AiSafetyTier.SILENT || (planMode && isReadOnly(action))` | `ui/viewmodel/AiConsoleViewModel.kt` 的 isReadOnly / processActions。注意 `safe_exec_shell` 在 AiPathGuard 中的档位是 LIGHT，但在计划模式下仍随只读集合自动放行——计划卡在批准**之前**已把每步档位与命令公示 |
| 失败 / 拒绝即暂停 | 任一步执行失败或被拒绝：该步标红（Failed / Denied），后续步标记「已跳过（Skipped）」，计划卡状态改为「已暂停」，按钮换成「修改计划 / 中止」；同时向模型回传一句「其中一步失败或被拒绝，客户端已暂停…」 | `ui/viewmodel/AiConsoleViewModel.kt`（planMode 分支 paused = true）+ AiPlanCardMiuix |
| 修改计划 / 中止 | 「修改计划」清空计划卡并把输入框预填「请调整上面的计划：」；「中止」清空计划卡并追加一条隐藏的用户说明，本回合结束不再续跑 | AiConsoleViewModel.revisePlan / abortPlan |
| 文件附件 | 输入行最左侧 📎 → 系统文件选择器（可多选）→ 选中后以 Chip 形式显示在输入框上方：「文件名 (大小) ✕」，✕ 单个移除 | `ui/screen/aiassistant/AiAttachments.kt`（rememberAttachmentPicker，ActivityResultContracts.GetMultipleContents） |
| 附件类型与上限 | 文本 .log / .txt；压缩包 .zip（只抽 .log / .txt 条目，最多 10 条、每条 4000 字符）；图片 .png / .jpg / .jpeg（单图上限 5 MB，发像素，见 §9 的 D-ac）；单个文件上限 8 MB，文本最多 64000 字符，随消息下发的附件文本总量上限 120000 字符 | `ui/screen/aiassistant/AiAttachments.kt` 的 MAX_FILE_BYTES / MAX_TEXT_CHARS / MAX_ARCHIVE_ENTRIES / MAX_ARCHIVE_ENTRY_CHARS / MAX_IMAGE_BYTES / MAX_PROMPT_CHARS / IMAGE_EXTENSIONS |
| 附件随消息发送 | 发送时附件正文进入一条隐藏用户消息（模型侧块标题「以下是客户端在你回答之前已经采集到的设备相关文件内容」），输入框与 Chip 同步清空；无输入但有附件时发送按钮仍可用 | promptBlock(attachments) + AiConsoleViewModel.runConversation |
| 快捷提示 | 空态三条快捷提示（清理模块缓存 / 检查 root 日志 / 分析模块结构） | ai_console_quick_1..3 |

---

## 4. 交互组件规格

### 4.1 BottomSheet 家族（三个，统一形态）

| Sheet | 触发 | 内容 | 底部 |
| --- | --- | --- | --- |
| 选择 API 提供商（P3） | P2 提供商行 | 搜索框 + 三分组列表（11 家） | 取消 |
| 选择模型（P8） | P2「获取模型列表」 | 状态区（loading / empty / error）或模型列表（最高 460dp） | 重试（仅 error）+ 取消 |
| 选择访问范围（P8） | P2 访问范围行 | 三行单选（label + summary） | 取消 |

统一规格：Miuix `OverlayBottomSheet(show, title, onDismissRequest)` / Material `ModalBottomSheet(containerColor = surfaceContainer)`；行内边距 20dp / 16dp；行尾选中态 Check 或 RadioButton；列表 heightIn(max = 460dp)；底部取消按钮用 primary 文字按钮配色。

### 4.2 闸门三级（弹窗家族）

| 级别 | 视觉 | 触发 | 可复用组件 |
| --- | --- | --- | --- |
| SILENT 无闸门 | 无弹窗，仅在 [AI_AGENT] 日志区滚动记录 | 范围内读文件、只读命令、应用清单读取 | 无需组件，写 AiAgentLog |
| LIGHT 轻确认 | 标准双按钮弹窗（标题 + 一句正文 + 取消 / 确认） | 单个权限授予 / 撤销、单模块启停、装模块、撤销上一步 | `ui/component/dialog/Dialog.kt`（rememberConfirmDialog.showConfirm / awaitConfirm）；Material ExpressiveConfirmDialog（`ui/component/material/ExpressiveDialog.kt`） |
| STRICT 强拦截 | 红色警告弹窗（见下） | 删除、批量 ≥3、非白名单命令、范围升档、make_module | 新增 AiExecConfirmDialog{Miuix,Material} |

**STRICT 弹窗视觉规格**（沿用早期约定的红色弹窗，作为 STRICT 级唯一样式）：

    +----------------------------------------------------------+
    |  ⚠ 确认执行系统命令                                        |
    |  AI 正在尝试调用 Root 权限，请确认是否执行以下操作：          |
    |  +------------------------------------------------------+  |
    |  |  (深色代码预览区，等宽字体，横向可滚)                  |  |
    |  +------------------------------------------------------+  |
    |  该操作可能修改系统核心文件，请谨慎确认                       |
    |                       [ 取消 ]      [ 确认执行 ]            |
    +----------------------------------------------------------+

| 规格项 | 值 |
| --- | --- |
| 标题 | ⚠ + 加粗 + 警告色（Material colorScheme.error；Miuix MiuixTheme.colorScheme.error） |
| 副标题 | 次级色正文 |
| 代码预览 | 深色背景（surfaceContainerHighest 反转或固定深色）、FontFamily.Monospace、softWrap = false + horizontalScroll、heightIn(max = 200.dp) |
| 红色警告 | colorScheme.error 正文 |
| 解锁方式 | **直接单击「确认执行」**：不再要求键入确认词，也不使用长按（见 §9 的 D-aa）。确认前的风险告知仍在：弹窗标题、终端块里的动作与目标、红色警告行、以及高危动作的红色按钮 |
| 按钮 | 左下取消（浅色文字按钮）→ 右下确认执行 |
| Miuix 限制 | Miuix ButtonDefaults 只有 textButtonColors / textButtonColorsPrimary，**无 danger 变体** ⇒ 确认按钮用 primary 文字按钮，红色语义靠标题与警告文本承载 |

---

## 5. 安全分级模型（动作 → 级别）

执行链固定为四段：客户端侦察 → 打包上下文 → 模型只输出 {"actions":[...]} → 客户端过范围闸门 → 分级闸门 → 执行 → 审计。模型永远不会绕过闸门。规则集中在 `data/agent/AiPathGuard.kt`，动作定义见 `data/agent/AiAction.kt`。

| 动作 | 级别 | 闸门 | 依据 |
| --- | --- | --- | --- |
| read_file / get_file_metadata / read_file_chunk / search_in_file / list_dir（范围内） | SILENT | 只写日志 | 只读无副作用 |
| root_app_list | SILENT | 只写日志 | 读取谁持有 root 不改变任何状态 |
| run_command：只读文件命令（ls cat head tail df du stat find grep wc readlink realpath file），且路径全部落在档位内 | SILENT | 只写日志 | 只读 |
| run_command：设备信息命令（id uname uptime getprop getenforce whoami） | SILENT | 只写日志 | 只读 |
| run_command：pm list / ksud module list / ksud su list | SILENT | 只写日志 | 清单读取 |
| run_command：STRICT_COMMANDS（rm rmdir dd mkfs flash reboot shutdown setenforce chmod chown insmod rmmod mount umount cp mv ln tee sh su toybox busybox） | STRICT | 强拦截 | 不可逆 / 破坏引导 |
| run_command：含 shell 元字符（`> < | ; & * ?`、反引号、换行、反斜杠） | STRICT | 强拦截 | 客户端无法预判命令影响 |
| run_command：find 带写标志（-delete / -exec / -execdir / -ok / -okdir / -fprint* / -fls） | STRICT | 强拦截 | 只读命令被改写成写操作 |
| run_command：其他未知命令 | STRICT | 强拦截 | 未知命令 |
| safe_exec_shell（SAFE_EXEC_PREFIXES 白名单查询通道，禁元字符） | LIGHT | 轻确认 | 白名单只读查询，但每次仍需用户确认 |
| grant_su / revoke_su（单包） | LIGHT | 轻确认 | 影响单个应用的权限，可重新授予 |
| disable_module / enable_module（单个） | LIGHT | 轻确认 | 可逆（写 / 删 disable 文件） |
| install_module | LIGHT | 轻确认 | 可卸载 |
| undo（撤销一条审计记录） | LIGHT | 轻确认 | 撤销本身可再撤销 |
| move_to_trash（删除的替代） | STRICT | 强拦截 | 目标不可预测，需强确认 |
| make_module | STRICT | 强拦截 | 载荷由模型自写，customize.sh 由 ksud 以 root 执行 |
| 批量操作（同一会话内 ≥3 个同类动作，BATCH_STRICT_THRESHOLD = 3） | STRICT | 强拦截 | 爆炸半径放大 |
| 文件访问范围升档 | STRICT | 强拦截 | 权限扩张 |
| 未知动作类型 | STRICT（fail-closed） | 强拦截 | 默认拒绝 |
| 关闭「允许 AI 执行 Shell 命令」后的一切命令类动作 | 拒绝（Deny） | — | allowShell = false 时 decideRunCommand / decideSafeExec 直接拒绝 |

**硬底线**：任何对系统的写操作**至少** LIGHT 级，不存在「静默写」。分级只降低**确认负担**，不降低**留痕要求**：每一次执行（含 SILENT）都必须写审计。

---

## 6. AI 访问范围三档

数据层：`data/agent/AiAccessScope.kt`（enum `NONE / DATA_ADB / ROOT_FS`，ordinal 即权限序，DEFAULT = NONE）；持久化键 `access_scope`（`data/repository/AiSettingsRepositoryImpl.kt` 的 KEY_ACCESS_SCOPE）；旧键迁移 `allow_module_dir` = true → DATA_ADB，false → NONE（`AiAccessScope.fromLegacyAllowModuleDir`）。

| 档位 | 文件系统语义 | 允许的动作 | 默认 |
| --- | --- | --- | --- |
| NONE 禁止访问 | 不读任何路径 | 应用清单读取；**授予 / 撤销 root 权限**（走 Natives.setAppProfile，不碰文件系统）；无本地取证功能 | 是（fail-closed） |
| DATA_ADB 允许访问 /data/adb/ | 读写 /data/adb/** | 上表全部 + 模块清单 / 配置文件读取、冲突检测（需本档） | 否 |
| ROOT_FS 允许访问根目录 / | 读任意路径；写一律 STRICT | 上表全部 + 全局日志 / 系统文件读取 | 否 |

**闸门收口（四处，逐一执行）**

1. read_file：目标路径不在当前档位覆盖范围内 → 拒绝并写审计 result = denied。
2. run_command：从命令串提取绝对路径 token；DATA_ADB 档下越界即拒；命令含重定向或组合符（大于号、双大于号、管道、分号、逻辑与、逻辑或、反引号、反斜杠）时，一律升级到 STRICT 或直接拒绝。
3. 写 / 删除动作：必须落在档位范围内，且级别不低于 LIGHT。
4. 本地取证功能（模块冲突检测）：要求 DATA_ADB 及以上，否则入口提示先提档。

**升档 / 降档**

| 方向 | 流程 | 审计 |
| --- | --- | --- |
| 升档 | 选择目标档 → STRICT 强拦截弹窗 → 单击确认 → 生效 | AiAuditEntry(kind = SCOPE_CHANGE, origin = user, target / before / after / scope, undoable = true)，不进「AI 操作历史」 |
| 降档 | 选择目标档 → 立即生效，无弹窗 | 同上（配置页「恢复上一档」即回到上一档） |
| 失败 | 越界访问被拒 | result = denied |

---

## 7. 与早期设计约定的关系

本节记录本规格与早期设计约定（「执行必弹窗」「侧边距统一 16dp」等）之间的收口关系；被替代的约定保留在这里，便于回溯决定。

| 早期约定 | 现行规格 | 理由 |
| --- | --- | --- |
| 「点代码块『执行』时强制弹出确认」 | 改为按 §5 分级：SILENT 不弹、LIGHT 轻确认、STRICT 强拦截 | 取消所有操作都强制弹窗，读取和分析静默执行，减少无效打扰 |
| 红色弹窗视觉（标题 / 副标题 / 深色代码预览 / 红色警告 / 取消 + 确认执行） | **原样保留**，作为 STRICT 级唯一样式（§4.2） | 视觉资产保留，只改触发条件 |
| 「必须经过强制 UI 确认拦截」的安全底线 | 保留并强化：写操作至少 LIGHT；STRICT 动作必须单击确认 | 底线不放宽，只减少无效打扰 |
| 主页入口卡 / 配置页 / 提供商 Sheet | 未改动 | 已实现 |
| 「允许 AI 读取和修改模块目录」开关（布尔） | 升级为 §6 的访问范围三档 | 需要「禁止访问 / 仅 /data/adb/ / 根目录」三档 |
| 控制台 / 文件上传 / 模块制作 | 均已实现：控制台为对话 + [AI_AGENT] 日志区；文件上传与模块制作已落地（含图片像素随消息发送） | 对话面板升级为控制台，优先级更高；延后项一并落地，登记为 §9 的 D-ab / D-ac / D-ad |
| 「执行高危命令前强制确认」开关 | 保留：默认开且置灰不可关 | 安全底线，不得被关闭 |

---

## 8. 状态与反馈

| 状态 | 触发 | 规格 |
| --- | --- | --- |
| Loading | 拉取模型列表 / 模型思考中 / 执行 Shell | 模型列表：居中环形进度（160dp 最小高）；控制台：消息级骨架 / 环形进度，不阻塞输入 |
| Empty | 模型列表为空 | 居中浅灰搜索图标 + 「未找到相关模型」（ai_model_list_empty） |
| Error（HTTP） | 401 / 非 2xx | 配置页输入框下方红字（error 槽）或红色 Snackbar；模型列表 Sheet 内显示 ai_model_list_error_http + 重试 |
| Error（网络 / 格式） | 超时 / 返回体无 data 数组 / 端点非法 | ai_model_list_error_network / malformed / endpoint，同一位置显示 |
| 执行被拒 | Root 未授予 | 面板内系统提示「执行失败：Root 权限未授予」，写审计 result = failed |
| 未配置 | 无 API 密钥或端点为空的控制台 | 控制台输入框置灰并提示「请先配置 API 参数」，提供跳转配置页按钮 |
| 越界被拒 | 目标超出访问范围 | 对话内提示当前档位与所需档位，提供「去提档」入口 |

---

## 9. 设计取舍登记

| # | 早期约定 | 现行 | 理由 | 状态 |
| --- | --- | --- | --- | --- |
| D-a | 侧边距统一 16dp | Miuix 12dp / Material 16dp（跟随所在页面惯例） | 无痕融入优先，避免与同模式设置页错位 4dp | 已裁决：保持现状，各随其 flavour 规范（见 D-z） |
| D-b | 所有执行必弹确认 | 按 §5 三级分级 | 读取与分析静默执行，减少无效打扰 | 已覆盖（§7） |
| D-c | API 密钥用密码键盘 | KeyboardType.Ascii + 掩码 + 眼睛图标 | 设计约定：密钥使用普通键盘 | 已实现 |
| D-d | 模块目录开关（布尔） | 访问范围三档（默认禁止访问） | 需要更细的范围控制 | 已实现 |
| D-e | 删除文件 | 移入 /data/adb/.ai_trash/ 而非 rm | 保留一键撤销能力 | 已实现 |
| D-f | 权限审查由 AI 逐条判定 | 本地确定性分级（§3.5 规则表）+ 一键进入控制台做 AI 深入审查 | 页面离线可用、结论可复核；AI 深审复用已实现的动作 / 审计通道，不新开第二条请求链路 | 已实现 |
| D-g | 冲突检测由 AI 判定 | 本地扫描（§3.6 规则表）+ 一键进控制台让 AI 解读 | 文件重叠是集合运算，本地算得准且不花 token | 已实现 |
| D-h | 全量读取阈值上限受模型上下文约束 | 默认直接放到 2048 KB（= 客户端文本上限 2 MB），另加成本预估行 | 旋钮由用户掌控，成本已在界面提示 | 已实现（风险登记见 §10 尾注） |
| D-i | 计划模式开关放在「模型名**旁**」 | 移入 composer 动作行，做成 Chat / Plan 胶囊 chip（既不在标题行、也不在标题下方独立一行） | 模式与工具入口统一收进输入区；标题行右侧已被清空控件占用，放不下第三个控件 | 已实现；位置是否采纳仍待裁决 |
| D-j | 计划模式仅自动放行只读动作 | `safe_exec_shell` 在 AiPathGuard 中档位为 LIGHT，但在计划模式下仍随只读集合自动放行 | 计划卡在批准**之前**已把每步档位与命令公示，一键执行即等于用户对该批的授权；且该动作本身只读（白名单） | 已实现；是否改为「连只读也逐个确认」仍待裁决 |
| D-k | 附件原样交给模型 | 单文件上限 8 MB；文本截断 64000 字符；zip 只抽 .log / .txt 前 10 条（每条 4000 字符）；图片行为见 D-ac | 手机端解析成本与 token 成本都要有上界 | 已实现 |
| D-l | 快捷策略模板只改开关 | 点模板同时写 allowShell 与访问范围；宽范围（ROOT_FS）仍走升权强拦截弹窗 | 模板的本质就是一次批量设置；放宽范围属高危，必须保留人工确认 | 已实现 |
| D-m | 输入区观感 | 重做为单容器结构（值与来源见 §3.8） | 旧结构「输入框 + 按钮同一行」靠 weight 挤压，达不到参考图观感 | 已实现 |
| D-n | 沿用 Miuix `TextField` | Miuix 侧改用 `androidx.compose.foundation.text.BasicTextField` + decorationBox 自绘占位 | Miuix `TextField` 自带圆角底色，放进新容器会出现「框中框」 | 已实现 |
| D-o | 空输入时发送按钮禁用 | 有附件（无文字）时也可发送 | 附件本身就是一次完整输入 | 已实现 |
| D-p | 标题行右侧「清空对话」文字按钮 | 改为垃圾桶图标按钮（Miuix `MiuixIcons.Delete` / Material `Icons.Outlined.Delete`，contentDescription 仍取 ai_console_clear） | 标题行只有模型名 + 该按钮，文字按钮占宽且与模型名抢视觉重心 | 已实现 |
| D-q | 输入框边缘遮挡下方文字 | 消息列表加 `contentPadding(top = 4.dp, bottom = 12.dp)`、composer 加 top = 12dp 外边距；自动滚动由「消息条数」改为「消息条数 + 尾部长度」触发 | 根因是列表末端文字被 composer 的直边从中间切断，而不是圆角缺失 | 已实现（源码级） |
| D-r | 标题行只剩一个裸图标、看不出含义；且清空无确认 | 清空改为「图标 + 短标签」胶囊（Row + clip(RoundedCornerShape(16.dp)) + background(surfaceContainerHigh) + clickable(enabled = 有消息, onClickLabel = ai_console_clear) + padding(horizontal = 12.dp)，内含 18dp Delete 图标 + ai_console_clear_short 标签），点击后弹共享确认弹窗（rememberConfirmDialog：ai_console_clear_confirm_title / _message / _button） | 清空需要二次确认，且单独一个图标无法表意 | 已实现 |
| D-s | 控制台文案硬编码中文 | 45 个用户可见文案改走 values/strings.xml + values-zh-rCN/strings.xml 同键资源；ViewModel 经全局 ksuApp.getString(...) 取（`ui/KernelSUApplication.kt` 的 lateinit var ksuApp，既有范式 `ui/viewmodel/ModuleRepoViewModel.kt`） | 英文设备此前会看到中文；文案观感必须在 EN 上同样成立 | 已实现；模型协议文本（动作执行结果回传、侦察前缀）与附件 prompt 文本仍为中文，因为它们不回显给用户 |
| D-t | 传输层异常直接冒泡成裸 Java 消息 | AiConsoleViewModel.describeFailure 增加 `is IOException` 分支，走 ai_console_error_network（网络错误 / Network error）拼接原始 detail | 断网时旧行为只显示 `Unable to resolve host \"api.deepseek.com\": No address associated with hostname`，没有本地化前缀（`data/remote/AiChatClient.kt` 把非 AiChatException 的 Throwable 原样抛出） | 已实现 |
| D-u | 触控目标与热区 | 模式 chip 外层 `Box.height(48.dp)` 承担点击、内层胶囊保持 28dp；附件 ✕ 用 `Box.size(48.dp)` + onClickLabel = ai_console_remove_attachment；日志面板只留 Row 一个热区（内层「展开 / 收起」仅 padding(horizontal = 8.dp, vertical = 12.dp)）；空态快捷提示 height(48.dp) + 尾部 Spacer(16.dp) | 48dp 是 Android 最小可点尺寸，28–32dp 的胶囊本身不构成合格热区；双热区会让单击语义不确定 | 已实现（源码级） |
| D-v | 链接色直接用主题 primary | 新增 aiLinkColor(base, surface)（`ui/component/aichat/AiChatCommon.kt`）：若 contrastRatio(base, surface) < MIN_LINK_CONTRAST（4.5f），就沿 lerp(base, Black/White, step/20f) 迭代最多 20 步取第一个达标色（兜底纯黑 / 纯白）；三处链接（推理卡 / 动作输出摘要 / 日志面板）改传「离链接最近的那层 surface」 | 主题 primary 取自系统动态色，可能低于正文 4.5:1 门槛；链接是「次要信息也要能读」的硬要求，不能靠系统壁纸决定 | 已实现 |
| D-w | 输出摘要按字符阈值自动展开（length <= 600） | 改为 `aiOutputAutoExpanded(output) = output.length <= 600 || aiOutputLineCount(output) <= 8`，并把行数计算改为 trimEnd('\n','\r') 后再数换行符 | 单行 1935 字符的输出因字符超阈值被判为「长」而默认折叠，而行数才是决定是否需要折叠的关键 | 已实现（源码级） |
| D-x | 行内代码与代码围栏不渲染 | `aiRichText(text, codeBackground)`（`ui/component/aichat/AiChatCommon.kt`）改写：围栏行（三个反引号起止）整行着 SpanStyle(Monospace, background)；行内 `code` 同样着等宽 + 背景；`**bold**` 保留 SemiBold；围栏标记行本身不再输出 | 旧实现先删掉反引号再按行加粗，等于把行内代码删成普通文字，模型回复里的命令与标识符失去视觉区分 | 已实现（源码级） |
| D-y | STRICT 弹窗代码块靠软换行显示长命令 | Terminal 块内 Text 加 `softWrap = false`，modifier 改为 `horizontalScroll(...) + verticalScroll(...)` | STRICT 弹窗里的命令常长于一行，软换行会把命令折成两行读不出边界；代码块应保留原始行长并可横向拖动 | 已实现（源码级） |
| D-z | （D-a 的用户裁决）侧边距统一 | **维持不统一**：Miuix 12dp / Material 16dp，各自与所在页面的既有卡片边距对齐 | 同一模式下与设置页 / 列表页错位 4dp 比「绝对统一」更显眼 | 已裁决（无代码改动） |
| D-aa | 「键入确认词才能解锁确认按钮」（STRICT 命令弹窗与范围提升弹窗共用该机制） | **两扇弹窗都改为单击确认按钮直接执行**：删除 keyword / typed / unlocked 状态、输入块与 `enabled = unlocked`，并删除已无引用的 4 对字符串（ai_access_scope_confirm_keyword / _hint、ai_console_confirm_exec_keyword / _hint） | 授权窗口直点确认即可，不需要输入；确认前的风险告知仍在 | 已实现；本条取代原先的键入解锁条目，并使旧验收项中的「键入解锁」描述失效 |
| D-ab | 模块制作 | 落地为「模块制作」页（`ui/screen/modulemaker/`）：本机拼装 zip（module.prop 在包根 + 自由文件 + customize.sh）→ 走 KsuCli.installModuleZip → `ksud module install <zip>`；模块 ID 校验与 ksud 的 validate_module_id 同规（`^[a-zA-Z][a-zA-Z0-9._-]+$`）；产物写 `cacheDir/modulemaker/<id>.zip`；不做签名 / META-INF | 读 `userspace/ksud` 的 install_module(zip) 后确认：它只要求包根有 module.prop，随后解压到 /data/adb/modules_update/<id>；故无需 META-INF，客户端也不必自建 FileProvider 通道 | 已实现 |
| D-ac | 附件图片只传文件名与大小，不发像素 | 图片改为**发像素**：读取为 bytes → Base64（NO_WRAP）→ 随消息发出（单图上限 `MAX_IMAGE_BYTES = 5 MB`，png / jpeg）；按 provider family 组装 content parts（OPENAI_COMPATIBLE 用 image_url + data URL；ANTHROPIC 用 image + source.base64）；文本附件行为不变 | 要求把图片像素理解从延后项提前；此前 data/remote 全域 0 命中图片通道，附件只有文件名与大小 | 已实现（编译通过；真机行为未验证，见 §10 的 A46） |
| D-ad | 模块制作要接进 AI：在对话 / 计划模式里说一句就产出并安装模块 | 新增 `make_module` 动作：`AiAction.MakeModule(draft: ModuleDraft, install: Boolean)` 携带整份草稿 → `AiActionParser.fromJson` 解析 id / name / version / versionCode（兼容 version_code）/ author / description / files（`[{path, content}]`）/ install_script / install（默认 true），id 走与 ksud 同规的正则、name 为空则降级为 unknown → `AiPathGuard.decide` **恒 STRICT**（与 install_module 的 LIGHT 有意不对称：载荷由模型自写，customize.sh 由 ksud 以 root 执行）→ `AiActionExecutor.makeModule` → `ModuleMaker.buildAndInstall`（复用 `ModulePackage.build` + `installModuleZip`），审计 kind = make_module（undoable = false，模块动作不可自动撤销）；解析到动作即 `ModuleDraftStore.publish(draft)`，模块制作页进入时 `take()` 预填并显示横幅；「让 AI 起草」入口先 `setPlanMode(true)` 再 `startKickoff`；深度校验复用 `ModuleDraftValidator`（MAX_FILES = 20 / MAX_FILE_BYTES = 64 KB / MAX_TOTAL_BYTES = 128 KB / MAX_INSTALL_SCRIPT_BYTES = 32 KB），错误翻成可修正中文回传模型 | 计划模式管线已存在，缺的是「模块即动作」这一步 | 已实现 |

---

## 10. 验收与核对清单

每项的「方式」给出可复现的核对手段：源码断言可检索文件与符号名，运行时步骤可在设备上重放。

| # | 核对项 | 方式 |
| --- | --- | --- |
| A1 | 主页 AI 入口卡位于「支持开发」上方 | 源码：`ui/screen/home/HomeMiuix.kt` 中 AiAssistantCard 在 InfoCard 之后、DonateCard 之前；运行时：主页自上而下的卡片顺序 |
| A2 | 配置页「获取模型列表」在 endpoint 为空或不合法时 disabled | 源码：AiConfigMiuix.kt / AiConfigMaterial.kt 的 canFetchModels；运行时：清空端点后按钮置灰 |
| A3 | 密钥输入框为普通键盘（非密码键盘） | 源码断言 KeyboardType.Ascii + PasswordVisualTransformation；运行时点击弹出普通键盘 |
| A4 | 「AI 访问范围」行显示当前档位名，默认「禁止访问」 | 源码：`AiAccessScope.DEFAULT = NONE`；运行时读该行文本 |
| A5 | 范围 Sheet 三行、默认选中「禁止访问」 | 源码：`AiAccessScope.options`；运行时打开范围 Sheet |
| A6 | 升档弹窗出现且「确认提升」初始即可点 | 源码：`ui/component/{miuix,material}/AiScopeElevationDialog.kt` 中无 keyword / enabled 门控；运行时点按钮 |
| A7 | 降档不弹窗、立即生效 | 源码：isElevationFrom 为 false 时不进入该弹窗；运行时切回低档 |
| A8 | 「执行高危命令前强制确认」开关为 on 且不可关闭 | 源码 + 运行时 checked = true、enabled = false |
| A9 | 每次档位变更写审计（含 before / after / undoable / `origin = user`），但不出现在 AI 操作历史 | 运行时读 `filesDir/ai_audit.jsonl` 的新增行，并打开操作历史确认无「范围变更」行 |
| A10 | Miuix 与 Material 两种模式均可用 | 双模式各走一轮 |
| A11 | 控制台动作分级：SILENT 无弹窗、LIGHT 走共享弹窗、STRICT 红色弹窗（单击确认） | 源码：`data/agent/AiPathGuard.kt`；运行时结合 [AI_AGENT] 日志 |
| A12 | 权限审查页列出真实 root 授权并给出 HIGH / MEDIUM / LOW 标签 | 源码：`ui/viewmodel/AiPermissionReviewViewModel.kt`；运行时读行与标签 |
| A13 | 撤销授权：弹 LIGHT 确认 → 授权列表减少 → ai_audit.jsonl 新增 su 条目 | 运行时 + 读审计文件 |
| A14 | 模块冲突检测页能扫出真实重叠（构造两个模块共用同一 system 文件） | 运行时构造后重新扫描 |
| A15 | 审计页展示历史记录，且对 A13 的 su 条目可一键撤销（恢复授权 + 追加 undo 条目） | 运行时 + 读审计文件 |
| A16 | 三页在 Miuix 与 Material 两种模式均可进入 | 双模式各走一轮 |
| A17 | 配置页底部显示 Token 成本预估，且随参数变化实时更新 | 源码：`AiTokenEstimate`；运行时改参数后复读该行 |
| A18 | 放宽后的默认值与新选项可见（轮次 50、单次上限 128000 字符、全量阈值 2048 KB） | 源码：`AiRounds` / `AiReadLimits`；运行时读选项行 |
| A19 | 计划模式：模型先出「任务计划卡」不立刻执行；点「一键执行全部」后只读动作直接跑、写入仍弹原有确认 | 运行时结合 [AI_AGENT] 日志（「计划模式：已生成 N 步计划，等待用户批准」→「静默执行：…」/「计划模式自动放行只读动作：…」） |
| A20 | 计划执行失败即暂停：失败步标红、后续步 Skipped、计划卡变 Paused、按钮换成「修改计划 / 中止」 | 运行时构造失败步骤（读取一个不存在的路径）后读计划卡 |
| A21 | 「修改计划」清空计划卡并预填输入框；「中止」清空计划卡并结束本回合 | 运行时读输入框文本 |
| A22 | 文件附件：📎 打开系统选择器、可多选、Chip 显示「文件名 (大小) ✕」、发送后模型能引用附件内容 | 运行时要求模型总结所附日志，回答中应出现日志内的异常类与行号 |
| A23 | 权限页快捷策略模板一行三项 + 脚注；点模板即改下方开关；宽范围模板弹升权强拦截 | 源码：`AiPolicyTemplate`；运行时读开关状态与弹窗 |
| A24 | composer 是单一 24dp 圆角容器：文字区在上独占整宽、动作行在下，容器内无第二层输入框 | 源码：`AiConsoleMiuix.kt` 的 RoundedCornerShape(24.dp) 与 heightIn(min = 44.dp, max = 148.dp)；运行时观察空态与长文本撑高 |
| A25 | 动作行顺序为 附件图标 ┃ 分隔线 ┃ Chat / Plan ┃ 发送圆，且每个可点区域 ≥ 48dp | 源码：`AiConsoleMiuix.kt` 的 size(48.dp) / height(48.dp)；运行时读控件边界 |
| A26 | 模式 chip 选中态正确（选中填充 secondaryContainer、未选透明 / 描边） | 运行时观察 chip 底色 |
| A27 | 附件 chip 出现在容器内、文字区上方，✕ 可单个移除，有附件时无文字也能发送 | 运行时读 chip 位置与发送按钮可用态 |
| A28 | 重设计未破坏计划模式管线（模式切换、生成计划卡、步骤 Proposed） | 运行时结合 [AI_AGENT] 日志与计划卡 |
| A29 | Material flavour 与 Miuix 结构一致 | 切换 UI Style 后逐项对照 |
| A30 | AI 操作历史只出现 AI 的动作（「撤销」条目不显示裸枚举，而是本地化范围名）；用户档位变更显示在配置页的「恢复上一档」 | 运行时读行副标题与展开详情 |
| A31 | 标题行「清空」是图标 + 短标签胶囊而非纯文字按钮 | 源码：`AiConsoleMiuix.kt` 的 clip(RoundedCornerShape(16.dp)) 胶囊；运行时对照 |
| A32 | 流式回复增长时列表尾部跟随，末行文字不被 composer 上边缘切断 | 源码：消息列表 `contentPadding(top = 4.dp, bottom = 12.dp)`；运行时观察末行与容器上边缘 |
| A33 | 空对话时「清空」胶囊为可见禁用态（不再与可点状态同形） | 运行时对照空 / 非空两种状态 |
| A34 | 点「清空」胶囊弹出二次确认；确认后消息与客户端日志一并清空 | 源码：ai_console_clear_confirm_title / _message / _button；运行时 EN 与 ZH 各一轮 |
| A35 | EN（en-US）设备上控制台文案与日志行全为英文 | 源码：45 个用户可见文案走同键资源；运行时把应用语言切到 en-US 后逐项对照 |
| A36 | 清空控件是「图标 + 短标签」胶囊；状态行显示访问范围与 shell 状态 | 运行时读胶囊文本与状态行 |
| A37 | 断网时错误文案本地化且保留原文明细 | 源码：`AiConsoleViewModel.describeFailure` 的 `is IOException` 分支；运行时断网后发一条消息 |
| A38 | 链接色在明暗两种底色上都 ≥ 4.5:1 | 源码：`AiChatCommon.kt` 的 aiLinkColor / MIN_LINK_CONTRAST = 4.5f；运行时在浅色与深色主题下各取一次样 |
| A39 | 动作输出按「字符 ≤ 600 或行数 ≤ 8」自动展开，且行数不含尾部空行 | 源码：aiOutputAutoExpanded / aiOutputLineCount；运行时发一条长单行输出 |
| A40 | 围栏代码块与行内代码渲染为等宽 + 背景，且围栏标记行不显示 | 源码：aiRichText；运行时发一条只含单个 fenced code block 的消息 |
| A41 | STRICT 弹窗代码块不软换行、可横向滚动 | 源码：`AiExecConfirmDialog{Miuix,Material}.kt` 的 softWrap = false + horizontalScroll；运行时用一条长命令触发 |
| A42 | 已被取代：STRICT 弹窗的「键入确认词」门控已移除，验收并入 A44 | — |
| A43 | Miuix 与 Material 的用户气泡几何一致（半径 / 底色 / 内边距 / 最大宽） | 源码：两侧均为 RoundedCornerShape(16.dp) + secondaryContainer + padding 12 / 10 + widthIn(max = 300.dp) |
| A44 | 两扇确认弹窗都不再要求键入确认词，确认按钮初始即可点 | 源码：`ui/component/{miuix,material}/AiExecConfirmDialog.kt` 与 `AiScopeElevationDialog.kt` 中无 keyword / typed / unlocked 状态；运行时分别由一次 STRICT 动作与一次升档触发 |
| A45 | 模块制作全流程：填表 → 实时预览包内文件 → 生成 zip → ksud 真实安装成功 | 源码：`ModuleMaker.buildAndInstall`；运行时走完「模块制作」页全流程并读安装器输出 |
| A46 | 附件图片以像素随消息发送（OpenAI 兼容 image_url / Anthropic image source） | 源码：`AiChatClient.kt` 的 contentOf 按 family 组装；`AiAttachments.kt` 的 MAX_IMAGE_BYTES = 5 MB；运行时在控制台附件选图后确认模型能描述图内内容（**待验证**） |
| A47 | STRICT 弹窗单击「确认执行」即执行，且动作 / 审计 / 撤销全链路闭环 | 运行时触发一次 move_to_trash，核对弹窗无输入框、确认按钮可点、审计新增两条、撤销可回滚 |
| A48 | 权限审查 / 冲突检测 / 模块制作页与 Material flavour 真机可用 | 运行时在 Miuix 与 Material 下各走一轮 |
| A49 | 在计划模式里说一句「做一个模块」即可产出并安装内核模块（make_module 全链路） | 运行时从「模块制作 → 让 AI 起草」进入，核对 [AI_AGENT] 日志、计划卡、STRICT 弹窗与安装器输出 |
| A50 | 动作卡「在模块制作页打开」跳转到模块制作页并预填整份草稿 | 源码：`ui/component/aichat/AiChatMiuix.kt` / `AiChatMaterial.kt` 逐条渲染动作卡时必须传 `onOpenModuleMaker`；`ModuleDraftStore.take()` 在屏幕的 `LaunchedEffect(Unit)` 中消费（导航条目重建而 ViewModel 被复用时 init 不再执行，草稿会丢）；运行时点该按钮 |

> **A17 已知风险（登记）**：全量读取阈值的默认值 2048 KB 恰好等于客户端可选的文本读取上限，一次全量读取按 `AiTokenEstimate` 的口径约 52 万 token（2048 × 1024 / 4），超过多数模型 128K 的上下文窗口，HTTP 层会返回 context-length 400（表现为控制台「请求失败：HTTP 400」）。当前实现把旋钮完全交给用户，预估行已把成本写在界面上；若后续出现该类 400，建议在 `readFileWindow` 增加按 token 预算的二次夹取（例如全量读取也受一个 token 上限约束，超出则回退到 `charCap = readChunkLimit` 并附提示）。

> **未在本文验证的项**：A46（图片像素随消息发送）只有编译级证据；A20 / A22 / A29 / A47 / A49 / A50 需要在设备上重放运行时步骤。其余条目的结论均可由源码直接核对。
