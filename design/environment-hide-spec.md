# 「环境隐藏」页面 UI 规格 + 底栏图标规格

> 适用范围：KernelSU Manager（`manager/app/src/main`，Jetpack Compose，Material3 + Miuix 双主题）。
> 本文是**设计规格**，不含业务逻辑；数值与组件以仓库现有实现为准，逐条标注来源文件。
> 图标实现：`manager/app/src/main/java/me/weishu/kernelsu/ui/icon/HideEnvironmentIcon.kt`；
> 图标资源：`design/hide-environment-icon.svg`。

---

## 0. 交付物与接入现状

| 交付物 | 路径 | 状态 |
| --- | --- | --- |
| 底栏图标（Kotlin `ImageVector`） | `manager/app/src/main/java/me/weishu/kernelsu/ui/icon/HideEnvironmentIcon.kt` | 已实现 |
| 同名 SVG（与 Kotlin 逐点一致） | `design/hide-environment-icon.svg` | 已实现 |
| Miuix 页面 | `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/hiding/HideEnvironmentMiuix.kt` | 已实现 |
| Material 页面 | `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/hiding/HideEnvironmentMaterial.kt` | 已实现 |
| 页面入口与状态 | `ui/screen/hiding/HideEnvironmentScreen.kt`、`HideEnvironmentUiState.kt`、`ui/viewmodel/HideEnvironmentViewModel.kt` | 已实现 |
| 文案资源 | `manager/app/src/main/res/values*/strings.xml` 的 `hide_environment*` 系列 | 已实现 |
| 本规格 | `design/environment-hide-spec.md` | 本文件 |

### 0.1 图标接口约定

- 包名 `me.weishu.kernelsu.ui.icon`，顶层 `val HideEnvironmentIcon: ImageVector`。
- `defaultWidth = 24.dp`、`defaultHeight = 24.dp`、`viewportWidth = 24f`、`viewportHeight = 24f`。
- 单色：`path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)`；最终颜色由调用方 `Icon(imageVector = ..., tint = ...)` 决定。
- 未引入新依赖：只使用 `androidx.compose.ui.graphics.{Color, PathFillType, SolidColor}`、`androidx.compose.ui.graphics.vector.{ImageVector, path}`、`androidx.compose.ui.unit.dp`。

### 0.2 底栏接入点

| 位置 | 文件 | 形式 |
| --- | --- | --- |
| Miuix 底栏 | `ui/component/bottombar/BottomBarMiuix.kt` | `BottomBarDestination.HideEnvironment(R.string.hide_environment_tab, HideEnvironmentIcon)` |
| Material 底栏 | `ui/component/bottombar/BottomBarMaterial.kt` | `Triple(R.string.hide_environment_tab, HideEnvironmentIcon, HideEnvironmentIcon)` |
| 大屏导航栏 | `ui/component/bottombar/NavigationRailMaterial.kt` | 第 3 项，同 Material 底栏 |
| 页面注册 | `ui/navigation3/Routes.kt`（`Route.HideEnvironment`）、`ui/MainActivity.kt`（pager 第 3 页） | — |

---

## 1. 图标设计规格

### 1.1 语义与设计理由

「环境隐藏」= 屏蔽 root 环境被检测。设计取**完整实心盾牌 + 一条 45° 负空间斜杠**：

- **盾牌** = 安全域 / 防护。
- **斜杠** = 遮蔽 / 屏蔽。斜杠不作为叠加描边画出，而是作为**减去的负空间**。理由：
  1. 单色向量若用同色描边叠加斜杠，斜杠不可见；若用异色则违反"单色 + 由 `Icon` tint 重新着色"的接口约定。
  2. 负空间方案让图标整体仍是**实心填充**，与底栏其余图标的光学重量一致——底栏现用图标全部来自 filled / Rounded 系列（`BottomBarMiuix.kt` 的 `Icons.Rounded.Cottage / Security / Extension / Settings`，`BottomBarMaterial.kt` 的 `Icons.Filled.* / Icons.Outlined.*`）。
- **斜杠只挖穿盾牌内部，不切断外轮廓**（切穿方案已否决，见 §1.6）。理由：
  1. 盾牌右下折面的斜率是 0.970，几乎与 45° 斜切平行，任何"切穿"方案都会在 24dp 下留下一片 1–2px 的孤立细条，读起来像杂点而不是盾尖。
  2. 轮廓完整 ⇒ 盾牌身份不被破坏，24dp 下第一眼仍是"盾牌"。
  3. 底栏 SuperUser 项用的 `Icons.Rounded.Security` 也是盾牌。若本图标再切成碎片，两个相邻项会出现两块"破碎的盾"。`Security` 的内部元素是**居中钥匙孔**，本设计是**贯穿式 45° 斜条**，这是二者在 24dp 下唯一可靠的区分特征。
- **原创性**：路径数据为本设计自行推导，非 Material 现成图标改装。与 Material 既有盾牌的差异：

| 特征 | Material `Security` | Material `Shield` | 本设计 `HideEnvironment` |
| --- | --- | --- | --- |
| 盾牌下半部 | 圆弧收敛 | 圆弧收敛 | **直线折面收敛**（faceted），与本仓库 logo 前景的几何 / 方角语言一致（`manager/app/src/main/res/drawable/ic_launcher_foreground.xml`） |
| 内部元素 | 居中钥匙孔（小、居中） | 无 | **3.00dp 的 45° 贯穿斜条**（横跨盾身，四角离轮廓 1.50–2.94dp） |
| 顶边 | 平直 | 平直 | 平直 + 2.35 半径圆肩 |

### 1.2 几何定义（24 × 24 viewport）

**安全区**：视觉内容落在 x ∈ [3.75, 20.25]、y ∈ [2.2, 21.8]，完全落在 2..22 安全区内；水平中点 (3.75 + 20.25) / 2 = **12.0**，垂直中点 (2.2 + 21.8) / 2 = **12.0**。宽 16.5 × 高 19.6。

**外轮廓（引导几何，不单独绘制）**

| 部位 | 数值 |
| --- | --- |
| 顶边 | y = 2.2，x 从 6.1 到 17.9 |
| 左上圆肩 | 从 (3.75, 4.55) 三次曲线到 (6.1, 2.2)，控制点 (3.75, 3.25)、(4.8, 2.2)（≈ 半径 2.35 的圆角） |
| 右上圆肩 | 从 (17.9, 2.2) 三次曲线到 (20.25, 4.55)，控制点 (19.2, 2.2)、(20.25, 3.25) |
| 竖直侧边 | x = 3.75 与 x = 20.25，y 从 4.55 到 13.8 |
| 右下折面 | 直线 (20.25, 13.8) → 盾尖 (12.0, 21.8)，斜率 8.0 / 8.25 = 0.970 |
| 左下折面 | 直线 (3.75, 13.8) → 盾尖 (12.0, 21.8)，斜率 −0.970（与右下折面对称） |

**负空间斜杠**

| 项 | 数值 |
| --- | --- |
| 角度 | 45°（长轴斜率 1.0000） |
| 长轴中线 | 直线 `y = x − 0.6`，中线线段从 (6.8, 6.2) 到 (16.2, 15.6) |
| 上边缘 | 直线 `y = x − 2.7214` |
| 下边缘 | 直线 `y = x + 1.5214` |
| 垂直条宽 | (1.5214 − (−2.7214)) / √2 = 4.2428 / 1.41421 = **3.0001 dp** |
| 条长 | √(9.4² + 9.4²) = **13.293 dp** |
| 四角坐标 | (7.8607, 5.1393)、(17.2607, 14.5393)、(15.1393, 16.6607)、(5.7393, 7.2607)（顺时针，构成 3.0 × 13.293 的正矩形） |
| 距轮廓最小余量 | A(5.7393, 7.2607) → 左边 x = 3.75：**1.99dp**；B(7.8607, 5.1393) → 顶边 y = 2.2：**2.94dp**；C(17.2607, 14.5393) → 右下折面：**1.55dp**；D(15.1393, 16.6607) → 右下折面：**1.50dp**。**全局最小 1.50dp > 0**，四角全部在盾内 |

**子路径（Kotlin 与 SVG 完全一致；斜杠是第二条子路径，被 EvenOdd 挖空）**

1. 盾牌轮廓（顺时针，闭合）
   `M 3.75,4.55 → C 3.75,3.25 4.8,2.2 6.1,2.2 → L 17.9,2.2 → C 19.2,2.2 20.25,3.25 20.25,4.55 → L 20.25,13.8 → L 12.0,21.8 → L 3.75,13.8 → Z`
   （`close()` 从 (3.75, 13.8) 竖直回到起点 (3.75, 4.55)，与左竖直侧边重合）
2. 45° 斜杠（顺时针，闭合，完全在子路径 1 内部）
   `M 7.8607,5.1393 → L 17.2607,14.5393 → L 15.1393,16.6607 → L 5.7393,7.2607 → Z`

### 1.3 填充规则：用 EvenOdd 精确挖空

两条子路径互不相交（斜杠严格位于盾牌轮廓内部，四角到轮廓的最小余量 1.50dp），因此 `PathFillType.EvenOdd` 的结果是精确的，不依赖容差：

| 区域 | 穿越数 | EvenOdd 结果 |
| --- | --- | --- |
| 只在盾牌轮廓内（斜杠外） | 1（奇） | 填充 |
| 只在斜杠内 | 2（盾 + 条，偶） | 挖空 |
| 在盾牌外 | 0（偶） | 不填充 |

- 不需要布尔路径运算：EvenOdd 对"一个形状完全包住另一个形状"本身就是精确定义，无需 `Path.subtract` / `combine`，也不需要把斜杠条带裁到轮廓上。
- 不要把斜杠条带的两端伸出盾牌之外：那样盾牌外的残段穿越数会变成 1（奇）而被填色，产生"黑色小尾巴"缺陷。
- 最紧的一处余量只有 1.50dp（斜条右下角到右下折面）。在 mdpi 24px 下表现为约 1.5px 宽的实心黑楔，仍是连续实心；若在真机上被误读为"盾被切断"，可把整条斜杠沿其长轴方向向左上平移 t：右下折面侧的余量 +t，左边与顶边侧的余量各 −0.707t。取 t = 0.287 可把四处余量均衡到 ≈ 1.79 / 2.74 / 1.84 / 1.79dp（代价：斜条位置略偏左上，四个角坐标全部改变，必须同步两个文件并重新渲染确认）。
- `PathFillType` 需 import `androidx.compose.ui.graphics.PathFillType`。

命令集仅用 `moveTo / lineTo / curveTo / close`。

### 1.4 与底栏其它图标的光学重量对照

| 图标 | 来源 | 视觉重量特征 |
| --- | --- | --- |
| `Icons.Rounded.Cottage / Security / Extension / Settings` | `BottomBarMiuix.kt` | 实心填充，内容约占 24dp 的 2..22 |
| `Icons.Filled.* / Icons.Outlined.*` | `BottomBarMaterial.kt` | 选中实心 / 未选中描边 |
| `HideEnvironmentIcon` | 本设计 | 实心填充 + 3.00dp 内部负空间条；内容 3.75..20.25 × 2.2..21.8 |

轮廓占满安全区（16.5 × 19.6），只挖去一条 3.00dp 宽的斜条，填充率接近 `Icons.Filled.Shield`，比 `Icons.Outlined.Shield` 实心得多。放入 Miuix 底栏（全 Rounded 实心）与 Material 底栏时视觉重量一致。

### 1.5 SVG 与 Kotlin 的一致性

`design/hide-environment-icon.svg` 使用与 Kotlin 相同的点表与命令顺序（单个 `<path>` 承载两条子路径，对应 Kotlin 的单个 `path { }` 块）：

| Kotlin | SVG |
| --- | --- |
| `path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)` | `<path fill="#000000" fill-rule="evenodd" …>` |
| `moveTo(3.75f, 4.55f)` | `M3.75 4.55` |
| `curveTo(3.75f, 3.25f, 4.8f, 2.2f, 6.1f, 2.2f)` | `C3.75 3.25 4.8 2.2 6.1 2.2` |
| `lineTo(17.9f, 2.2f)` | `L17.9 2.2` |
| `curveTo(19.2f, 2.2f, 20.25f, 3.25f, 20.25f, 4.55f)` | `C19.2 2.2 20.25 3.25 20.25 4.55` |
| `lineTo(20.25f, 13.8f)` | `L20.25 13.8` |
| `lineTo(12.0f, 21.8f)` | `L12 21.8` |
| `lineTo(3.75f, 13.8f)` + `close()` | `L3.75 13.8 Z` |
| `moveTo(7.8607f, 5.1393f)` | `M7.8607 5.1393` |
| `lineTo(17.2607f, 14.5393f)` | `L17.2607 14.5393` |
| `lineTo(15.1393f, 16.6607f)` | `L15.1393 16.6607` |
| `lineTo(5.7393f, 7.2607f)` + `close()` | `L5.7393 7.2607 Z` |

SVG 中额外的引导几何注释（安全区、斜条中线与宽度、轮廓顶点）不参与渲染。两份文件的数值改一处必须同步改另一处；仓库内没有构建期校验能拦住不一致，任何改动都要在同一次提交里同时修改两个文件。

### 1.6 设计取舍：已否决的方案

| 方案 | 内容 | 否决原因 |
| --- | --- | --- |
| A | 盾牌 x 4.4..19.6、竖直侧边仅 4.7..10.7、斜缝宽 2.60dp，斜缝切穿轮廓成上下两片 | 侧边过短使盾读作"圆角方块"；下片被斜缝削成细条，24dp 下盾牌身份不成立 |
| B | 盾牌 x 5.0..19.0、侧边 y 4.5..13.0、盾尖 (12, 21.4)，同样切穿；缝位取 45° 高位、33.69°、45° 中位 | 轮廓本身成立，但下片一律读作细月牙 / 钩。根因：折面斜率 1.2 与 45° 切线近乎平行，拐角处楔形最薄仅 2.89dp，越靠盾尖越薄 |
| C | 扫描缝角度 / 宽度（40° / 35° / 45° / 50°）与"轮廓完整 + 内部负空间条"两种思路 | 切穿方案在 24px 下仍留孤立碎块；"轮廓完整 + 内部 45° 负空间条"无碎块、盾牌身份最强，且能靠贯穿斜条与同为盾形的 `Icons.Rounded.Security`（居中钥匙孔）区分 → 最终方案（§1.2） |

---

## 2. 页面组件映射（只列仓库中真实存在的组件）

> 原则：不编造仓库不存在的组件。以下每个组件均在仓库中有真实定义。

### 2.1 页面骨架

| 主题 | 骨架 | 定义位置 |
| --- | --- | --- |
| Material | `ExpressiveScaffold(topBar = { TopAppBar(...) }, contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))` | `ui/component/material/ExpressiveScaffold.kt` |
| Material | TopBar 颜色统一用 `expressiveTopAppBarColors()` | 同上 |
| Material | 内容容器 `Column(Modifier.padding(innerPadding).verticalScroll(rememberScrollState()))`，横向边距由各分组自加 | 各 `*Material.kt` 页面 |
| Miuix | `Scaffold(topBar = { TopAppBar(...) }, popupHost = { }, contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))` | `ui/screen/home/HomeMiuix.kt` |
| Miuix | `LazyColumn(Modifier.fillMaxHeight().overScrollVertical()..., contentPadding = innerPadding)` | `ui/screen/home/HomeMiuix.kt`、`ui/screen/settings/SettingsMiuix.kt` |

### 2.2 分组与分节

| 主题 | 用法 | 定义 / 用法位置 |
| --- | --- | --- |
| Material | 分组：`SegmentedColumn(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp), content = listOf { ... })` | `ui/component/material/SegmentedList.kt`；`ui/screen/settings/SettingsMaterial.kt` 等 |
| Material | 分组标题 `title = stringResource(...)`，内部渲染为 `Text(style = MaterialTheme.typography.titleSmall, color = colorScheme.primary, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))` | `ui/component/material/SegmentedList.kt` |
| Material | 组内条目间距 `Arrangement.spacedBy(2.dp)` | `ui/component/material/SegmentedList.kt` |
| Miuix | 分组 = 一张 `Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth())`，不用 SegmentedColumn | `ui/screen/settings/SettingsMiuix.kt` |
| Miuix | 分组小标题用 `SmallTitle(...)` | `ui/screen/modulerepo/ModuleRepoMiuix.kt`、`ui/screen/appprofile/AppProfileMiuix.kt` |

### 2.3 本页使用的具体组件

| 组件 | 签名要点 | 定义位置 | 用途 |
| --- | --- | --- | --- |
| `TonalCard` | `(modifier, containerColor = colorScheme.surfaceBright, contentColor, shape = MaterialTheme.shapes.large, enabled, onClick, onLongClick, interactionSource, content)` | `ui/component/material/TonalCard.kt` | Material 状态总览卡、风险说明卡 |
| `SegmentedSwitchItem` | `(icon: ImageVector?, title: String, summary: String?, colors, checked: Boolean, enabled: Boolean, onCheckedChange)` | `ui/component/material/SegmentedList.kt` | Material 隐藏策略开关区 |
| `SegmentedListItem` | `(modifier, onClick, onLongClick, enabled, colors, interactionSource, headlineContent, overlineContent, supportingContent, leadingContent, trailingContent)` | `ui/component/material/SegmentedList.kt` | Material 状态 / 自检结果行 |
| `StatusTag` / `StatusTagMaterial` | `(label: String, modifier, backgroundColor: Color, contentColor: Color)`；`RoundedCornerShape(4.dp)` + `padding(vertical = 2.dp, horizontal = 4.dp)` | `ui/component/statustag/StatusTag.kt`、`StatusTagMaterial.kt`、`StatusTagMiuix.kt` | 总览卡上的运行模式 / 状态标签 |
| `WarningCard`（Miuix） | `(message: String, modifier, level: WarningLevel = WarningLevel.Error, onClick, action)` | `ui/component/miuix/WarningCard.kt` | Miuix 风险 / 提示说明卡 |
| `SwitchPreference`（Miuix） | `(title, summary, startAction, enabled, checked, onCheckedChange)` | `ui/screen/settings/SettingsMiuix.kt` 等 | Miuix 隐藏策略开关区 |
| `BasicComponent`（Miuix） | `(title, summary, startAction, endActions)` | `ui/screen/home/HomeMiuix.kt` 等 | Miuix 条目 / 空态 / 不支持态 |
| `ExpressiveSwitch` | `(checked, onCheckedChange, modifier, thumbContent, enabled, colors, interactionSource, showThumbIcon)` | `ui/component/material/ExpressiveSwitch.kt` | 仅在自绘行时使用；`SegmentedSwitchItem` 已内部封装 |

**明确不存在、不要使用的组件**：仓库中没有 `EnvironmentHideCard`、`SelfCheckList`、`RiskCard`、`HideStatusCard` 之类的现成组件。本页一律复用上表组件，不新建同名概念组件。

---

## 3. 页面视觉规格 · 信息层级与状态

### 3.1 已实现的分区结构

页面本身不引入新图标与新技术栈，全部由既有组件拼装。两个主题的函数一一对应：

| 分区 | Miuix（`ui/screen/hiding/HideEnvironmentMiuix.kt`） | Material（`ui/screen/hiding/HideEnvironmentMaterial.kt`） |
| --- | --- | --- |
| 页面入口 | `HideEnvironmentPagerMiuix`（Scaffold 骨架） | `HideEnvironmentPagerMaterial`（`ExpressiveScaffold` + `LargeFlexibleTopAppBar`） |
| 能力不支持提示 | `UnsupportedCardMiuix`（`Card` + `BasicComponent`） | `UnsupportedColumnMaterial`（`SegmentedColumn`） |
| 环境状态总览 | `EnvironmentStatusCardMiuix` | `EnvironmentStatusColumnMaterial` |
| 隐藏开关区 | `HidingSwitchesCardMiuix`（3 个 `SwitchPreference`） | `HidingSwitchesColumnMaterial`（`SegmentedSwitchItem`） |
| 运行模式横幅 | `RuntimeModeBannerMiuix` | `RuntimeModeBannerMaterial` |
| 一键隐藏 | `OneKeyHideCardMiuix`（含 LSPosed 依赖提示、救砖提示、执行按钮） | `OneKeyHideColumnMaterial` |
| 快速安装 | `QuickInstallCardMiuix` | `QuickInstallColumnMaterial` |
| 隐藏资源包列表 | `HidingPackCardMiuix` + `HidingPackEntryMiuix` + `HidingPackEndActionMiuix` + `HeaderComponentMiuix` + `RefreshPreference` | `HidingPackColumnMaterial` + `HidingPackEntryMaterial` + `StatusRow` |
| 分步执行日志 | `StepLogMiuix` | `StepLogMaterial` |

顶部信息层为「环境状态总览」，其下依次是开关区、一键 / 快速操作区、资源包列表与日志。这一顺序与主页「先结论、后明细」的范式一致：本页的首要问题是**当前是否已隐藏成功**，逐条明细属于第二信息层。

### 3.2 状态与配色 token

| 状态 | 表达 | 配色 |
| --- | --- | --- |
| 能力不可用 | Miuix `WarningCard(level = WarningLevel.Notice)`；Material 复用 `TonalCard` + `Row(padding(horizontal = 16.dp, vertical = 12.dp))` + `bodyMedium` | 动态取色：容器 `tertiaryContainer` / 内容 `onTertiaryContainer`；非动态：亮 `0xFFFFF0DB` / 暗 `0xFF3E2F1B`，内容色 `0xFFF5A623` |
| 存在风险项 | 同上，改用 `WarningLevel.Error` | 动态取色：容器 `errorContainer` / 内容 `onErrorContainer`；非动态：亮 `0xFFF8E2E2` / 暗 `0xFF310808`，内容色 `0xFFF72727` |
| 已就绪 | 与主页状态卡同构 | Miuix `secondaryContainer`（非动态亮 `0xFFDFFAE4` / 暗 `0xFF1A3825`）；Material `secondaryContainer` |
| 未知 / 无法判定 | 文案回落到 `hide_environment_mode_unknown` 等"未知"资源 | 沿用所属容器的文本色 |

`WarningCard` 正文为 `fontSize = 14.sp`，容器 `Row(Modifier.fillMaxWidth().padding(16.dp))`，标题与可选 `action` 之间 `Arrangement.SpaceBetween`。

`enabled` / summary 的三态派生沿用仓库既有约定（`"supported"` / `"unsupported"` / `"managed"`，见 `ui/screen/settings/SettingsMaterial.kt`、`SettingsMiuix.kt`），不自造第四态。

### 3.3 间距 / 圆角 / 字号

**Material 侧**

| 项 | 数值 | 来源 |
| --- | --- | --- |
| 分组横向边距 | `start = 16.dp, end = 16.dp` | 各 `*Material.kt` 的 `SegmentedColumn` 调用 |
| 分组下边距 | `bottom = 13.dp` | 同上 |
| 组内条目间距 | `Arrangement.spacedBy(2.dp)` | `ui/component/material/SegmentedList.kt` |
| 分段圆角（首 / 末条） | `16.dp` | 同上 |
| 分段圆角（中间条） | `4.dp` | 同上 |
| 单条分组圆角 | `MaterialTheme.shapes.large` | 同上 |
| 卡片圆角 | `MaterialTheme.shapes.large` | `ui/component/material/TonalCard.kt` |
| 卡片 / 列表容器色 | `colorScheme.surfaceBright` | 同上 |
| 分组标题 | `titleSmall`，色 `colorScheme.primary`，`padding(start = 16.dp, bottom = 8.dp)` | `ui/component/material/SegmentedList.kt` |
| 列表主文本 / 副文本 | `bodyLarge` / `bodyMedium` + `colorScheme.onSurfaceVariant` | 同上 |
| 全局正文字号 | `bodyLarge = 16.sp`、`lineHeight = 24.sp`、`letterSpacing = 0.5.sp`（本仓库唯一被覆盖的 typography 槽位） | `ui/theme/Type.kt` |
| 状态标签 | `RoundedCornerShape(4.dp)`、`padding(vertical = 2.dp, horizontal = 4.dp)` | `ui/component/statustag/StatusTagMaterial.kt` |

**Miuix 侧**

| 项 | 数值 | 来源 |
| --- | --- | --- |
| 页面横向边距 | `HomeDimens.PageHorizontal` | `ui/screen/home/HomeDimens.kt`；见 `design/home-ui-review.md` |
| 分组卡间距 | 每张 `Card` 之间 `Arrangement.spacedBy(HomeDimens.CardSpacing)` | 同上 |
| 分组卡内边距 | `Column(Modifier.padding(HomeDimens.CardInner))` | 同上 |
| 组内条目间距 | `Arrangement.spacedBy(12.dp)` | `ui/screen/home/HomeMiuix.kt` |
| 总览卡大字 | 主 `22.sp` + `FontWeight.SemiBold`；副 `15.sp`；运行模式角标 `16.sp` + `FontWeight.Medium` | 同上 |
| 总览卡内边距 | `padding(16.dp, 14.dp)`（主内容区）/ `padding(16.dp, 10.dp)`（底部） | 同上 |
| 条目标题 / 副文本 | `MiuixTheme.textStyles.headline1.fontSize` + `FontWeight.Medium`、色 `colorScheme.onSurface`；副文本 `MiuixTheme.textStyles.body2.fontSize`、色 `colorScheme.onSurfaceVariantSummary`、`padding(top = 2.dp)` | 同上 |
| 条目行间距 | `HomeDimens.InfoRowBottom`（末行 `0.dp`） | `ui/screen/home/HomeDimens.kt` |
| 开关 / 跳转行图标 | `Modifier.padding(end = 6.dp)`，色 `colorScheme.onBackground` | `ui/screen/settings/SettingsMiuix.kt` 等设置项 |
| 日志行容器 | `Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp))` | `HideEnvironmentMiuix.kt` 的 `StepLogMiuix` |

底栏自身的尺寸见 `design/home-ui-review.md` 的底栏一节，本文不重复。

### 3.4 底栏 5 tab 布局结论

**现状**：Miuix 与 Material 底栏均已包含「环境隐藏」项，共 5 项。

**每项可用宽度**

- `FloatingBottomBar` 按 `tabWidthPx = (totalWidthPx - 8.dp.toPx()) / tabsCount` 均分（栏内部 `Row` 的 `padding(horizontal = 4.dp)` 使两侧各占 4dp），见 `ui/component/FloatingBottomBar.kt`。
- 栏的实际宽度由调用方传入的 `modifier` 决定。以 360dp 宽屏幕、栏左右各留 28dp 的常见口径计算：可用宽 304dp，减去内部 8dp 后，4 项时每项 74.0dp、5 项时每项 59.2dp。
- 代码中的下限保护为 `Modifier.defaultMinSize(minWidth = 56.dp)`（`BottomBarMiuix.kt`），该值刻意低于 5 项时的每项宽度。

**结论 1（defaultMinSize 的语义边界）**：`FloatingBottomBarItem` 内部把 `modifier.fillMaxHeight().weight(1f)` 挂在同一条 Modifier 链上，Row 会给 weight 子项分配**精确宽度约束**（min == max == 分配值）。`Modifier.defaultMinSize` 只抬高最小值、不会突破上层传入的 `maxWidth`，因此在精确约束下它只能作为下限保护，**不能用来保证 5 项时的可点区域**；可点区域以实际分配宽度为准。

**结论 2（标签截断，两个主题行为不一致）**

| 主题 | 标签写法 | 长标签行为 |
| --- | --- | --- |
| Miuix | `Text(text = item.label, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)` | **不出现省略号**：`softWrap = false` 禁止换行、`overflow = Visible` 允许画出边界之外 → 长标签被裁切或压到相邻项上 |
| Material | `Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)` | 正常省略号截断 |

「环境隐藏」4 个 CJK 字在 Miuix 底栏的宽度 ≈ 4 × 11sp = **44dp**（CJK 字形前进宽度 ≈ 1em），5 项时每项 59.2dp 可容纳，左右各余约 7.6dp。同一位置若翻译成较长的拉丁文案（16 字符 × ≈5.5dp ≈ 88dp），必然越界，且 Miuix 侧不会出现省略号。

**标签长度预算**：CJK ≤ 4 字，拉丁 ≤ 7 字符。默认英文资源 `hide_environment_tab` 取值为 `Hiding`，符合该预算。

**结论 3（角标不构成挤压风险）**

- 角标只对 `SuperUser` 与 `Module` 返回非空，见 `ui/component/bottombar/BottomBar.kt` 的 `badgeFor`。
- 角标绘制在**图标**上（`BadgedBox(badge = { badge() }) { icon() }`；Material 侧为 `NavigationIconWithBadge`），不占用标签宽度。
- 另有全局开关 `LocalEnableNavigationBadge` 可整体关闭角标。
- 只要不给「环境隐藏」扩展角标，5 项下角标与标签之间不存在宽度竞争。

**结论 4（Material 侧图标状态）**：`BottomBarMaterial` 的 `items` 是 `Triple(labelRes, selectedIcon, unselectedIcon)`，选中用 `Icons.Filled.*`、未选中用 `Icons.Outlined.*`（`icon = if (selected) selectedIcon else unselectedIcon`）。本次交付的 `HideEnvironmentIcon` 是**实心**矢量，只有一个，因此该项选中 / 未选中传的是同一个图标，选中态只由 `ShortNavigationBarItem` 的指示器体现，与其它项的"实心 / 描边"对比不一致。若需要完全对齐，应补一个描边变体（把两条子路径改为 `stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f` 的空心轮廓）或改用 Material 现成图标过渡——属新增交付物。

**结论 5（大屏分栏）**：`useNavigationRail` 为真时改用 SideRail 而非底栏，`ui/component/bottombar/NavigationRailMaterial.kt` 已包含第 5 项；如新增更多目的地，两份 rail 文件需同步。

**结论 6（索引一致性）**：底栏项与 pager 页按下标一一对应（`mainPagerState.selectedPage == index`），`badgeFor` 也使用 `BottomBarDestination.*.ordinal`。因此**枚举顺序、底栏列表顺序、pager 页顺序三者必须一致**。

### 3.5 空状态与错误态

| 场景 | 表达 | 复用来源 |
| --- | --- | --- |
| 尚未执行 / 无自检项 | 居中容器 `Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center)` 内 `Text(..., textAlign = TextAlign.Center)` | `ui/screen/module/ModuleMaterial.kt` |
| 能力探测失败 / 加载失败 | `Column(horizontalAlignment = Alignment.CenterHorizontally)`：提示 `R.string.network_offline`（色 `onSurfaceVariant`）+ `Spacer(12.dp)` + `Button` 重试（`R.string.network_retry`） | `ui/screen/modulerepo/ModuleRepoMaterial.kt` |
| 首帧闪烁规避 | `val contentReady = hadDataOnEntry || rememberContentReady()` | `ui/util/DeferredContent.kt` |
| Miuix 不支持 / 空 | `Card { BasicComponent(title = ..., summary = ..., startAction = { Icon(Icons.Rounded.ErrorOutline, ..., tint = colorScheme.onBackground) }) }` | `ui/screen/home/HomeMiuix.kt` |
| Miuix 加载失败卡 | `WarningCard(message, level = WarningLevel.Error, action = { TextButton(...) })` | `ui/component/miuix/WarningCard.kt` |

新增文案所需的字符串资源位于 `manager/app/src/main/res/values*/strings.xml`。本页复用既有的 `R.string.feature_status_unsupported_summary`、`R.string.feature_status_managed_summary`、`R.string.network_offline`、`R.string.network_retry`。

### 3.6 结果行推荐图标（全部为仓库已用过的 Rounded / Filled 系列）

| 结果 | 图标 |
| --- | --- |
| 通过 | `Icons.Rounded.CheckCircle` |
| 通过（Miuix 水印 / 行内） | `Icons.Rounded.CheckCircleOutline` |
| 风险 | `Icons.Rounded.Warning` |
| 阻断 / 不支持 | `Icons.Rounded.Block` |
| 不支持（Miuix） | `Icons.Rounded.ErrorOutline` |
| 进入子页 | `Icons.AutoMirrored.Filled.KeyboardArrowRight` |
| 本页 / 本项主图标 | `HideEnvironmentIcon` |

---

## 4. 实现注意事项

1. **图标接入**：Miuix 侧在 `BottomBarDestination` 增项并传 `HideEnvironmentIcon`；Material 侧 `items` 为三元组，实心 / 描边变体的取舍见 §3.4 结论 4。
2. **标签长度预算**：CJK ≤ 4 字 / 拉丁 ≤ 7 字符；Miuix 侧长标签不省略（§3.4 结论 2）。
3. **每项宽度下限**：`defaultMinSize(minWidth = 56.dp)` 只作下限保护，不能保证可点区域（§3.4 结论 1）。
4. **索引一致性**：枚举顺序 = 底栏顺序 = pager 页顺序（§3.4 结论 6）。
5. **大屏 rail**：新增目的地须同步 `NavigationRailMaterial.kt` / `NavigationRailMiuix.kt`。
6. **不要顺手改** `ui/theme/Type.kt`（仅覆盖 `bodyLarge`）；页面排版一律引用现有 token。
7. **两个图标文件必须同改**：`HideEnvironmentIcon.kt` 与 `hide-environment-icon.svg` 是同一份设计的手写副本，仓库没有构建期校验（§1.5）。

---

## 5. 验证方式

### 5.1 编译

```bash
cd manager
./gradlew assembleDebug
```

按仓库 `AGENTS.md`，构建前必须先把 ksud 二进制放到 `manager/app/src/main/jniLibs/arm64-v8a/libksud.so`，否则构建失败。

### 5.2 图标两文件一致性

从 `HideEnvironmentIcon.kt` 的路径命令中按出现顺序抽出浮点数，与 `design/hide-environment-icon.svg` 的 `d` 属性中按出现顺序抽出的数字逐位比较；两者必须完全相同、顺序相同，命令字母序列为：

```
moveTo, curveTo, lineTo, curveTo, lineTo, lineTo, lineTo, close,
moveTo, lineTo, lineTo, lineTo, close
```

### 5.3 布局实测

- 在 360dp 宽设备上打开底栏，确认 5 项标签均不越界、不与相邻项重叠；
- 逐一检查各语言 `strings.xml` 中 `hide_environment_tab` 的取值是否满足长度预算；
- 大屏（分栏）设备上确认 SideRail 包含第 5 项。

---

## 附录 A. 几何自洽性验算

以下数值由 §1.2 的点表直接推得，可用于回归核对：

| 验算 | 结果 |
| --- | --- |
| 轮廓是否闭合 | 子路径 1 末点 (3.75, 13.8) 与起点 (3.75, 4.55) 的连线正是左竖直侧边，`close()` 不产生额外可见边 |
| 左右是否居中对称 | 轮廓中点 (3.75 + 20.25) / 2 = 12.0；顶边中点 (6.1 + 17.9) / 2 = 12.0；盾尖 x = 12.0 = 24 / 2 |
| 折面斜率 | 右 (21.8 − 13.8) / (12.0 − 20.25) = 8.0 / (−8.25) = **−0.9697**；左 **+0.9697**，左右镜像 |
| 斜条是否为 45° | (14.5393 − 5.1393) / (17.2607 − 7.8607) = 9.4 / 9.4 = **1.0000** |
| 四角是否共线于两条平行边 | 上边 7.8607 − 2.7214 = 5.1393、17.2607 − 2.7214 = 14.5393；下边 5.7393 + 1.5214 = 7.2607、15.1393 + 1.5214 = 16.6607 |
| 斜条宽度 | 两边常数项差 1.5214 − (−2.7214) = 4.2428；垂直宽 = 4.2428 / √2 = **3.0001 dp** |
| 斜条中线 | (−2.7214 + 1.5214) / 2 = **−0.6** → 中线 `y = x − 0.6` |
| 斜条是否为矩形 | 长边 √(9.4² + 9.4²) = **13.293**；短边 √(2.1214² + 2.1214²) = **3.000**；两边点积 9.4 × (−2.1214) + 9.4 × 2.1214 = **0** ⇒ 四角构成正矩形 |
| 是否完全在轮廓内 | 四角到最近轮廓边的距离：A 1.9893dp、B 2.9393dp、C 1.5502dp、D 1.5041dp（右下折面 `8x + 8.25y = 275.85`，法线长 √132.0625 = 11.4918）。全局最小 1.5041dp > 0 |
| 是否落在 2..22 安全区 | x ∈ [3.75, 20.25] ⊂ [2, 22]；y ∈ [2.2, 21.8] ⊂ [2, 22] |
| 填充率（解析估算） | 盾面积 ≈ 255 dp²（矩形段 152.6 + 顶部含圆肩 ≈ 36.4 + 底部三角 66.0）；挖空 3.000 × 13.293 ≈ 39.9 dp² → 保留 ≈ 215 dp²，填充率 ≈ 84% |

## 附录 B. 已知限制（待验证）

1. 图标几何已按 §附录 A 解析验算，但 `PathFillType.EvenOdd` 在 Compose 运行时渲染下的表现未做验证；应在预览或真机上确认一次。斜条右下角到右下折面的余量只有 1.50dp，是全图最紧的一处，建议在低密度屏（mdpi 24px）上专门确认不会被误读为"盾被切断"。
2. 5 项底栏的实际布局按 §3.4 的公式推得，未在 360dp 宽度设备上实测——**待验证**。
3. 各语言翻译标签的实际长度取决于 `strings.xml` 取值；Miuix 侧长标签不省略（§3.4 结论 2）——**待验证**。
4. Material 侧选中 / 未选中使用同一实心图标，与其余项的实心 / 描边对比不一致（§3.4 结论 4）——**待验证**（属设计取舍）。
