# 主页 UI 尺寸规范 · KernelSU Manager

> 适用范围：KernelSU Manager 主页（HomePager），Jetpack Compose，Miuix 与 Material3 双主题。
> 本文是**尺寸与结构规范**，不含业务逻辑；数值以仓库现有实现为准，逐条标注来源符号。
> 依据源文件：`HomeDimens.kt`、`HomeMiuix.kt`、`HomeMaterial.kt`、`BottomBarMiuix.kt`、`BottomBarMaterial.kt`、`FloatingBottomBar.kt`、`ui/theme/Type.kt`。

---

## 1. 布局常量（唯一来源）

布局常量集中定义在 `manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/HomeDimens.kt`。两个主题的页面水平内边距与卡片间距必须引用同一常量，不得在 `HomeMiuix.kt` / `HomeMaterial.kt` 内联重复数值——该文件存在的目的就是阻止两种模式漂移。

| 常量 | 值 | 用途 |
| --- | --- | --- |
| `HomeDimens.PageHorizontal` | `12.dp` | Miuix `LazyColumn` 与 Material `Column` 的水平内边距，决定卡片左右边界 |
| `HomeDimens.PageTop` | `12.dp` | 顶栏与首张卡片之间的留白 |
| `HomeDimens.CardSpacing` | `12.dp` | 相邻卡片之间的纵向间距 |
| `HomeDimens.CardInner` | `16.dp` | 卡片内容列的内边距 |
| `HomeDimens.InfoRowBottom` | `16.dp` | 信息卡一行内容与其下一行之间的底部间距；卡片末行改用 `0.dp`，底部呼吸由 `CardInner` 提供 |

`HomeDimens.kt` 的源码注释引用本文档；其指向的即本节。改动任一常量时必须同步更新本表。

---

## 2. 主页结构与卡片顺序

两种主题的卡片顺序一致：

1. 更新卡（`AnimatedVisibility`，仅当 `state.hasUpdate` 时组合）
2. 警告卡（按状态条件渲染，见 §2.1）
3. 状态卡 `StatusCard`
4. 信息卡 `InfoCard`
5. AI 助手卡 `AiAssistantCard`
6. 支持开发卡 `DonateCard`
7. 了解 KernelSU 卡 `LearnMoreCard`
8. 底部留白 `Spacer(Modifier.height(bottomInnerPadding))`

### 2.1 警告卡触发条件

| 条件字段 | 文案资源 |
| --- | --- |
| `state.showManagerPrBuildWarning` | `R.string.home_pr_build_warning` |
| `state.showKernelPrBuildWarning` | `R.string.home_pr_kernel_warning` |
| `state.showVersionMismatchWarning` | `R.string.home_version_mismatch` |
| `state.showGkiWarning` | `R.string.home_gki_warning` |
| `state.showUAPIMisMatchWarning` | `R.string.uapi_mismatch` |
| `state.showRequireKernelWarning` | `R.string.require_manager_version` 或 `R.string.require_kernel_version`（按管理器与内核版本号大小择一） |
| `state.showRootWarning` | `R.string.grant_root_failed` |

Miuix 侧警告卡为 `ui/component/miuix/WarningCard.kt`，Material 侧是 `ui/screen/home/HomeMaterial.kt` 内的同名私有组件。

### 2.2 Miuix 骨架

`Scaffold`（`topBar = TopBar`，`contentWindowInsets = systemBars + displayCutout`，仅 Horizontal 方向）内含 `LazyColumn`：

- `LazyColumn` 的 `Modifier.padding(horizontal = HomeDimens.PageHorizontal)`，`contentPadding = innerPadding`；
- 单个 `item` 内是 `Column(Modifier.padding(top = HomeDimens.PageTop), verticalArrangement = Arrangement.spacedBy(HomeDimens.CardSpacing))`；
- 顶栏标题取 `R.string.app_name`，外层 `BlurredBar` 负责模糊背景。

### 2.3 Material 骨架

`ExpressiveScaffold`（`topBar = LargeFlexibleTopAppBar`）内容区为 `Column`：

- `Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = HomeDimens.PageHorizontal)`；
- `verticalArrangement = Arrangement.spacedBy(HomeDimens.CardSpacing)`。

---

## 3. 状态卡

### 3.1 Miuix

实现为单张 `Card`（`Modifier.fillMaxWidth()`），内容是一个 `Box` 叠加三层：

| 层 | 内容 | 尺寸 / 位置 |
| --- | --- | --- |
| 水印 | `Icon(Icons.Rounded.CheckCircleOutline)` | `Modifier.size(110.dp)`，外层 `Box(Modifier.fillMaxSize().offset(27.dp, 31.dp), contentAlignment = BottomEnd)` |
| 运行模式角标 | `Text(workingMode)`，`16.sp` + `FontWeight.Medium` | 容器 `padding(16.dp, 10.dp)`，`Alignment.BottomStart` |
| 结论与版本 | 主文案 `22.sp` + `FontWeight.SemiBold`；`Spacer(1.dp)`；版本行 `15.sp` + `FontWeight.Medium` | 容器 `padding(16.dp, 14.dp)`，`Alignment.TopStart` |

配色：

| 分支 | 容器色 | 水印色 |
| --- | --- | --- |
| 动态取色（`isDynamicColor`） | `colorScheme.secondaryContainer` | `colorScheme.primary.copy(alpha = 0.8f)` |
| 非动态 · 暗色 | `Color(0xFF1A3825)` | `Color(0xFF36D167)` |
| 非动态 · 亮色 | `Color(0xFFDFFAE4)` | `Color(0xFF36D167)` |

主文案为 `R.string.home_working`，安全模式与越狱模式下追加 `R.string.safe_mode` / `R.string.jailbreak_mode` 后缀；版本行取 `R.string.home_working_version`。未激活分支改用 `Card + BasicComponent`（标题 `R.string.home_not_installed`、副标题 `R.string.home_click_to_install`），不支持分支使用 `R.string.home_unsupported` / `R.string.home_unsupported_reason`。

### 3.2 Material

`StatusCard` 是 `Column(verticalArrangement = Arrangement.spacedBy(13.dp))`，实际只渲染一张 `Surface`：

| 项 | 值 |
| --- | --- |
| 容器 | `Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)` |
| 容器色 | 已激活 `colorScheme.secondaryContainer`；否则 `colorScheme.errorContainer` |
| 内容 | `ListItem`（前导图标 + 主文案 + 副文本 + 尾部标签/按钮） |
| 前导图标 | 已激活 `Icons.Outlined.CheckCircle`；未安装 `Icons.Outlined.Warning`；不支持 `Icons.Outlined.Block` |
| 主文案 | `MaterialTheme.typography.titleMediumEmphasized` |
| 副文本 | `MaterialTheme.typography.bodyMedium`，`contentColor.copy(alpha = 0.7f)` |
| 尾部 | 运行模式 `StatusTag`（`primary` / `onPrimary`）；仅越狱态改用 `Button` 配 `error` / `onError` |

### 3.3 约定

- 状态卡是全页唯一"一眼看状态"的卡片，同一事实不得在信息卡中重复呈现。
- 水印图标固定 110dp 并向右下偏移，是否被卡片圆角裁切需要真机目视确认（见 §7 待办）。
- 三个叠加层都以 `fillMaxSize` 撑满卡片，卡片高度由主内容层的 `padding` 与字号决定；调整字号时必须同时复核水印位置。

---

## 4. 信息卡

### 4.1 Miuix

`Card { Column(Modifier.fillMaxWidth().padding(HomeDimens.CardInner)) }`，行由内部 `InfoText` 组合：

| 元素 | 字号 | 颜色 | 间距 |
| --- | --- | --- | --- |
| 标题 | `MiuixTheme.textStyles.headline1.fontSize` | `colorScheme.onSurface` | 无 |
| 内容 | `MiuixTheme.textStyles.body2.fontSize` | `colorScheme.onSurfaceVariantSummary` | `padding(top = 2.dp, bottom = bottomPadding)` |

`bottomPadding` 默认取 `HomeDimens.InfoRowBottom`，末行显式传 `0.dp`。标题一律 `FontWeight.Medium`。

行序固定为六行：管理器版本、内核、设备型号、系统指纹、SELinux、Seccomp。SELinux 与 Seccomp 的取值经本地化映射（`selinux_status_*` / `seccomp_status_*`），未知值回落到"未知"文案。

### 4.2 Material

`TonalCard { Column(padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) }`，行间用 `Spacer(Modifier.height(16.dp))`。标签用 `bodyLarge`，内容用 `bodyMedium` + `colorScheme.onSurfaceVariant`。行序与 Miuix 侧一致。

### 4.3 约定

- 信息卡为只读长文本区，最多六行，不放置操作入口。
- 行底距取 `HomeDimens.InfoRowBottom`；末行不留白。
- 长文本（系统指纹）允许自然换行，不为它单独调整行距。

---

## 5. 行动卡（AI 助手 / 支持开发 / 了解 KernelSU）

三张卡同构：标题 + 单行副标题 + 尾部图标，整卡可点击。

| 卡片 | 标题资源 | 副标题资源 | 尾部图标 | 点击行为 |
| --- | --- | --- | --- | --- |
| AI 助手 | `R.string.home_ai_assistant_title` | `R.string.home_ai_assistant_summary` | `ui/icon/AiAssistantIcon.kt` | `actions.onOpenAiConfig` |
| 支持开发 | `R.string.home_support_title` | `R.string.home_support_content` | `MiuixIcons.Link` | 打开 `https://patreon.com/weishu` |
| 了解 KernelSU | `R.string.home_learn_kernelsu` | `R.string.home_click_to_learn_kernelsu` | `MiuixIcons.Link` | 打开 `R.string.home_learn_kernelsu_url` |

Miuix 侧一律为 `Card(modifier = Modifier.fillMaxWidth()) { BasicComponent(...) }`，尾部图标 `tint = colorScheme.onSurface`。

Material 侧为 `TonalCard` + `Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))`，标题 `titleSmall`、副标题 `bodyMedium` + `onSurfaceVariant`，AI 卡尾部为 `Icons.Rounded.SmartToy`。

**约定**：三张卡的内边距必须与信息卡同级（Miuix 侧走 `BasicComponent` 默认值，**不得**再传 `insideMargin`），以保证同屏卡片的文本左边界一致。

---

## 6. 底栏

### 6.1 目的地

`BottomBarDestination` 共 5 项，顺序即 pager 页序：

| 下标 | 枚举 | 标签资源 | 图标 |
| --- | --- | --- | --- |
| 0 | `Home` | `R.string.home` | `Icons.Rounded.Cottage` |
| 1 | `SuperUser` | `R.string.superuser` | `Icons.Rounded.Security` |
| 2 | `HideEnvironment` | `R.string.hide_environment_tab` | `HideEnvironmentIcon` |
| 3 | `Module` | `R.string.module` | `Icons.Rounded.Extension` |
| 4 | `Setting` | `R.string.settings` | `Icons.Rounded.Settings` |

Material 侧为 `Triple(label, selectedIcon, unselectedIcon)`；隐藏环境一项选中与未选中都使用同一个实心 `HideEnvironmentIcon`，其余项在 `Icons.Filled.*` 与 `Icons.Outlined.*` 间切换。

### 6.2 尺寸

| 主题 | 容器 | 尺寸 / 排版 |
| --- | --- | --- |
| Miuix（浮动底栏开启） | `FloatingBottomBar` | 栏高 `64.dp`、内边距 `padding(4.dp)`、选中高亮 pill 高 `56.dp`；容器色 `surfaceContainer`，模糊开启时为 `surfaceContainer.copy(0.4f)` |
| Miuix（浮动底栏关闭） | `NavigationBar` + `NavigationBarItem` | 每项 `Modifier.weight(1f)` |
| Miuix 标签 | `Text` | `fontSize = 11.sp`、`lineHeight = 14.sp`、`maxLines = 1`、`softWrap = false`、`overflow = TextOverflow.Visible` |
| Material | `ShortNavigationBar` + `ShortNavigationBarItem` | 容器色 `colorScheme.surfaceContainer`；标签 `maxLines = 1`、`overflow = TextOverflow.Ellipsis` |

浮动底栏每项的 `Modifier.defaultMinSize(minWidth = 56.dp)` 是下限保护值。

### 6.3 约定

- **标签长度预算**：CJK ≤ 4 字，拉丁 ≤ 7 字符（默认英文标签 `Hiding`）。Miuix 侧不做省略，超长标签会越界或压到相邻项。
- **宽度下限**：360dp 屏、5 个 tab 时每项可用宽约 59.2dp，`defaultMinSize` 的下限必须小于该值。
- **索引一致性**：枚举顺序、底栏列表顺序、pager 页顺序三者必须一致；角标按 `BottomBarDestination.*.ordinal` 定位，三者同步即不会错位。
- **大屏分栏**：`shouldShowSplitPane()` 为真且未启用 Miuix 浮动底栏时改用 NavigationRail，新增目的地须同步 `NavigationRailMaterial.kt` / `NavigationRailMiuix.kt`。

---

## 7. 两模式差异与待办

### 7.1 已知差异

| 项 | Miuix | Material |
| --- | --- | --- |
| 页面水平 / 卡间距 | `HomeDimens` | `HomeDimens`（同源） |
| 卡片内边距 | 信息卡 `HomeDimens.CardInner`；行动卡走 `BasicComponent` 默认值 | 行动卡与警告卡字面量 `16.dp / 12.dp`；信息卡字面量 `16 / 16 / 14 / 12.dp` |
| 卡片标题字号 | `MiuixTheme.textStyles.headline1.fontSize` | `titleSmall` |
| 卡片正文 | `MiuixTheme.textStyles.body2.fontSize` | `bodyMedium`；仓库仅在 `ui/theme/Type.kt` 覆写 `bodyLarge`（`16.sp` / `lineHeight = 24.sp` / `letterSpacing = 0.5.sp`） |
| 信息卡行底距 | `HomeDimens.InfoRowBottom`，末行 `0.dp` | `Spacer(16.dp)` |
| 状态卡内部间距 | 由三层 `padding` 决定 | `Arrangement.spacedBy(13.dp)` |

### 7.2 待办（待验证）

1. Material 侧的卡片内边距与状态卡间距仍是字面量，未引用 `HomeDimens`；两模式的行距与标题标度差异是否需要统一，属设计决策，尚未裁决。
2. 状态卡 110dp 水印叠加 `offset(27.dp, 31.dp)` 后是否被 `Card` 圆角裁切，代码可推但未经目视确认——**待验证**。
3. 底栏在窄于 360dp 的屏幕、以及翻译标签超过长度预算时的表现未经实机确认——**待验证**。

---

## 8. 验收方式

### 8.1 常量来源检查

```bash
# 两个主题的主页必须引用同一组常量
rg -n "HomeDimens" manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/

# 主页不应再出现内联的页面水平 / 卡间距数值
rg -n "padding\(horizontal = 1[26]\.dp\)|spacedBy\(1[23]\.dp\)" \
  manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/HomeMiuix.kt \
  manager/app/src/main/java/me/weishu/kernelsu/ui/screen/home/HomeMaterial.kt
```

预期：第一条列出两个文件的引用；第二条在页面级布局上无命中（`HomeMaterial.kt` 中 `StatusCard` 的 `spacedBy(13.dp)` 属于状态卡内部，见 §7.1）。

### 8.2 构建

```bash
cd manager
./gradlew assembleRelease
```

按仓库 `AGENTS.md`，构建前必须先把 ksud 二进制放到 `manager/app/src/main/jniLibs/arm64-v8a/libksud.so`，否则 Gradle 构建失败。

### 8.3 实机核对

- 主页所有卡片的左边界应与 `HomeDimens.PageHorizontal` 一致；
- 三张行动卡与信息卡的文本左边界应完全相同；
- 信息卡相邻两行标题顶点的间距 = 标题行高 + `2.dp` + 正文行高 + `HomeDimens.InfoRowBottom`；
- 切换 UI 模式后重复上述三项，两模式应给出相同结果。
