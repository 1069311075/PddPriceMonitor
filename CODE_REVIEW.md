# PDD Price Monitor 代码审查报告

> 审查日期：2026-08-19
> 审查范围：app/src/main/java 下全部 Kotlin 文件、build.gradle.kts、AndroidManifest.xml

---

## 总体评价

项目整体功能完整，UI 设计细腻，动画细节丰富，体现了较高的工程完成度。核心的 OCR 解析逻辑（`ProductTextParser`）针对拼多多页面做了大量 heuristics 优化，思路扎实。

主要问题集中在：**架构分层不够清晰**、**核心文件过大（上帝类）**、**全局可变状态较多**、**数据库查询存在 O(n) 全表扫描性能瓶颈**。以下按优先级分类列出具体改进建议。

---

## 高优先级（High）

### H1. ScreenCaptureService 上帝类 — 职责严重过载

**问题所在**  
`ScreenCaptureService.kt` 约 1090 行，一个 Service 同时承担了：
- MediaProjection 管理（前台服务生命周期、VirtualDisplay、ImageReader）
- 悬浮窗 UI 构建（20+ 个 View 引用，全部用原生 View 手写布局）
- 动画逻辑（光圈脉冲、球色过渡、字符切换、面板淡入淡出等 10+ 动画）
- OCR 调用与结果解析
- 数据库操作（Repository 直接持有）
- 拖拽交互、键盘管理、价格格式化

**改进方向**  
按职责拆分为多个类：
- `FloatingBallManager` — 悬浮球 UI 展示、状态切换、动画
- `ResultPanelManager` — 识别结果面板的显示、编辑、保存交互
- `ScreenCaptureController` — MediaProjection / VirtualDisplay / ImageReader 管理
- `OverlayDragHelper` — 拖拽与边缘吸附逻辑
- 用 Compose 替代原生 View 编写悬浮窗 UI（Compose 可直接 attach 到 WindowManager），可大幅减少 UI 代码量

---

### H2. MainActivity.kt 过于庞大 — UI 组件未拆分

**问题所在**  
`MainActivity.kt` 约 1640 行，所有 Compose UI 都在一个文件里，包含 20+ 个 `@Composable` 私有函数。虽然 Compose 允许多个函数在一个文件，但按功能模块拆分更利于维护。

**改进方向**  
按功能拆分为多个文件，例如：
- `ui/components/TopBar.kt`
- `ui/components/OcrStartCard.kt`
- `ui/components/StatCards.kt`
- `ui/components/SearchBox.kt`
- `ui/components/ProductCard.kt`
- `ui/components/PriceLineChart.kt`
- `ui/components/ProductHistoryDetail.kt`
- `ui/screen/PriceMonitorScreen.kt` — 组合各组件

---

### H3. 全局可变状态（object 单例）存在线程安全与内存泄漏风险

**问题所在**  
以下全局单例对象持有可变状态，可在任意线程写入：

- `PddForegroundState`（`object`）— `_isPddForeground`、`_lastPackageName`、`_accessibilityConnected`、`_usageAccessGranted`
- `MonitorDebugState`（`object`）— `_info`
- `ScreenCaptureService.isRunning`（伴生对象 `var`）

风险：
1. 虽然用了 `MutableStateFlow`，但 `object` 的生命周期与应用进程一样长，Service 销毁后状态仍残留
2. `isRunning` 是一个普通 `var`，不是线程安全的（虽然当前都在主线程调用，但隐性约束容易被破坏）
3. 全局状态使组件间耦合严重，难以单元测试

**改进方向**  
- 使用依赖注入（如 Hilt）提供单例，而不是 `object` 声明
- 或封装到一个 `MonitorState` 类中，由 `PddMonitorApp` 持有，通过依赖注入传递
- `isRunning` 改用 `AtomicBoolean` 或也纳入 StateFlow

---

### H4. 数据库全表加载 + 内存中模糊匹配 — 性能瓶颈

**问题所在**  
`ProductRepository` 中 `findPriceComparison`、`upsertLowerPrices`、`saveManualProduct` 都调用了 `dao.getAllOnce()` 把全部商品加载到内存，再用 `TitleMatcher` 逐条计算 Levenshtein 距离找最佳匹配：

```kotlin
// ProductRepository.kt 第 32 行
val matched = matcher.findBestMatch(product.normalizedTitle, dao.getAllOnce())
```

`TitleMatcher.findBestMatch` 对每条记录做 O(n*m) 的编辑距离计算。当商品数量达到几百上千条时，每次 OCR 保存都会触发一次全表扫描 + 全量字符串比较，性能会显著下降。

**改进方向**  
1. **引入更高效的匹配算法**：用 N-gram / 倒排索引做候选召回，再对候选集做 Levenshtein 精排
2. **数据库层面过滤**：先按 normalizedTitle 的长度范围、前缀等条件做粗筛，减少候选集
3. **缓存全部商品列表**：Repository 内部维护一个内存缓存（Flow 订阅），避免每次都查数据库
4. **考虑用 FTS（全文搜索）**：Room 支持 FTS4，可以做更高效的文本匹配

---

### H5. 缺少依赖注入框架 — 可测试性差

**问题所在**  
目前获取 Repository 和 Database 的方式是直接强转 Application：

```kotlin
// MainActivity.kt 第 150 行
val repository = ProductRepository((application as PddMonitorApp).database.productPriceDao())

// ScreenCaptureService.kt 第 165 行
repository = ProductRepository((application as PddMonitorApp).database.productPriceDao())
```

这导致：
- 无法在测试中替换 Mock Repository
- 类之间硬编码依赖关系
- 生命周期管理不清晰

**改进方向**  
引入 **Hilt**（Jetpack 推荐的 DI 框架）：
- `@HiltAndroidApp` 标注 Application
- `@AndroidEntryPoint` 标注 Activity 和 Service
- `@Provides` 提供 Database、Dao、Repository 实例
- ViewModel 使用 `@HiltViewModel` + 构造函数注入

---

### H6. Room 数据库 exportSchema = false — 迁移风险

**问题所在**  
```kotlin
// AppDatabase.kt 第 13 行
exportSchema = false
```

关闭了 schema 导出，导致：
- 无法在版本控制系统中追踪数据库结构变更
- 迁移测试困难
- 无法自动生成迁移验证报告

**改进方向**  
- 设置 `exportSchema = true`
- 在 build.gradle.kts 中指定 schema 输出目录：
  ```kotlin
  ksp {
      arg("room.schemaLocation", "$projectDir/schemas")
  }
  ```
- 将 schemas 目录纳入版本控制

---

### H7. 错误处理不完善 — 用户感知不足

**问题所在**  
1. OCR 识别出错时，只更新了 `MonitorDebugState`，主界面 Debug 信息区域只显示一行文字且有省略，用户很难知道出错了
2. `ScreenCaptureService.captureOnce()` 中 `catch (error: Throwable)` 捕获所有异常但只显示灰色感叹号，没有错误详情
3. 数据库操作失败（如插入冲突）没有错误回调
4. 悬浮窗权限被拒绝时，没有明确的引导和状态提示
5. `TextRecognizerClient` 的 `suspendCancellableCoroutine` 在协程取消时是空实现（`invokeOnCancellation { }`），未取消 ML Kit 任务

**改进方向**  
- 建立统一的错误状态模型（sealed class），区分网络错误、OCR 错误、权限错误、数据库错误
- Service 通过广播 / Flow / EventBus 将错误传递给主界面
- `invokeOnCancellation` 中调用 `task.cancel()`
- 悬浮窗权限被拒时展示明确的引导说明和跳转按钮

---

### H8. ScreenCaptureService 内存泄漏风险

**问题所在**  
Service 中持有大量 View 引用（`overlayView`、`resultPanel`、`titleEdit`、`priceEdit` 等 15+ 个 View 变量），这些 View 间接持有 Context（Service）引用。虽然 `onDestroy()` 中有清理，但存在风险：

1. 动画对象（`ringAnimator`、`ballColorAnimator`、`glyphAnimator` 等）在 Service 销毁时取消，但如果取消不彻底可能持有 View 引用
2. `scope` 使用 `Dispatchers.Default`，如果其中有长时间运行的协程持有 Service 引用，可能导致泄漏
3. `MediaProjection.Callback` 是匿名内部类，隐式持有 Service 引用

**改进方向**  
- 将悬浮窗 UI 逻辑移到独立的类中，与 Service 解耦
- 使用 `WeakReference` 或明确的 attach/detach 生命周期
- 协程 scope 使用 `Dispatchers.Main.immediate` 或确保所有后台任务在 `onDestroy` 时被正确取消
- `MediaProjection.Callback` 改为静态内部类 + 弱引用

---

## 中优先级（Medium）

### M1. MainActivity 业务逻辑过多 — 应下沉到 ViewModel

**问题所在**  
`MainActivity.kt` 中包含：
- 价格格式化（`formatPrice`、`formatTime` 等 6 个格式化函数）
- 搜索文本归一化（`normalizeSearchText`）
- 今日记录统计（`isToday`、`todayCount`、`updatedCount`）
- 投影授权逻辑、悬浮窗权限检查逻辑

这些业务逻辑放在 UI 层，难以单元测试。

**改进方向**  
- 纯格式化函数抽到 `util/FormatUtils.kt` 或 `data/PriceFormatter.kt`
- 搜索、统计等逻辑放到 `MainViewModel` 中
- 权限检查与服务启动逻辑封装到 `capture/CaptureOrchestrator.kt` 之类的类中

---

### M2. 颜色/样式常量重复定义

**问题所在**  
`ScreenCaptureService.kt` 和 `MainActivity.kt` 中各自定义了一套几乎相同的颜色常量：

```kotlin
// ScreenCaptureService.kt 第 59-67 行
private val BrandRed = Color.parseColor("#E02E24")
private val BrandRedSoft = Color.parseColor("#FFF1F0")
private val FreshGreen = Color.parseColor("#1DC981")
...

// MainActivity.kt 第 131-139 行
private val BrandRed = Color(0xFFE02E24)
private val BrandRedSoft = Color(0xFFFFF1F0)
private val FreshGreen = Color(0xFF1DC981)
...
```

不仅重复，而且定义方式不一致（`Color.parseColor` vs `Color(0xFF...)`）。

**改进方向**  
- 统一使用 Compose Color 的定义方式
- 将设计系统抽到 `ui/theme/Color.kt` 和 `ui/theme/DesignSystem.kt`
- Service 中的原生 View 颜色也从同一个来源获取

---

### M3. ProductRepository.upsertLowerPrices 每次插入后重新全量查询

**问题所在**  
```kotlin
// ProductRepository.kt 第 59 行
existing = dao.getAllOnce()  // 每次插入后重新查全部
```

在批量插入循环中，每插入一条商品就重新查询整张表。这是 O(n^2) 的数据库操作。

**改进方向**  
- 插入后手动维护 `existing` 列表（追加新插入的 item），而不是重新查询
- 或者使用事务，先做所有匹配计算，再批量插入/更新

---

### M4. 缺少单元测试

**问题所在**  
整个项目没有任何测试代码。以下模块特别适合写单元测试：
- `TitleMatcher` — 字符串相似度计算
- `ProductTextParser` — OCR 文本解析（输入 Text 对象，输出 DetectedProduct）
- `ProductRepository` — 商品匹配、价格更新逻辑
- `FrameDiffer` — 帧差异计算

**改进方向**  
- 为 `TitleMatcher` 编写 JUnit 测试（纯函数，无依赖）
- 为 `ProductTextParser` 编写参数化测试，覆盖各种页面布局
- 为 Repository 编写 Room 数据库集成测试
- 使用 MockK 模拟 DAO 层测试 Repository 逻辑

---

### M5. SimpleDateFormat 非线程安全

**问题所在**  
```kotlin
// MainActivity.kt 第 1616-1620 行
private val fullTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
...
```

`SimpleDateFormat` 不是线程安全的。虽然注释说明"全部在主线程调用"，但这是一个隐性约束，未来如果有人在协程中调用就会出问题。

**改进方向**  
- 使用 `java.time` API（`DateTimeFormatter` 是线程安全的），minSdk=29 完全支持
- 或每次使用时创建新实例（但性能稍差）
- 或使用 `ThreadLocal` 包装

---

### M6. ImageReaderBitmapSource 每次创建两个 Bitmap

**问题所在**  
```kotlin
// ImageReaderBitmapSource.kt 第 18-26 行
val paddedBitmap = Bitmap.createBitmap(...)
paddedBitmap.copyPixelsFromBuffer(buffer)
val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, it.width, it.height)
paddedBitmap.recycle()
return cropped
```

每次截图都创建两个 Bitmap（padded + cropped），在高频调用时会产生大量内存分配和 GC 压力。

**改进方向**  
- 探索直接使用 `BitmapFactory.Options` 或其他方式跳过 padding
- 考虑使用 Bitmap 池复用（如 Glide 的 BitmapPool）
- 当前是手动触发 OCR，频率不高，所以影响有限，但如果将来做自动监测就会成为瓶颈

---

### M7. FrameDiffer 持有上一帧完整 Bitmap

**问题所在**  
```kotlin
// FrameDiffer.kt 第 12 行
private var previous: Bitmap? = null
```

`FrameDiffer` 始终持有上一帧的完整分辨率 Bitmap（如 1080x2400 = ~10MB），且每次都要 `copy` 一份新的。当前代码中 `FrameDiffer` 似乎定义了但未被使用（全局搜索没有实例化），如果未来启用需注意内存压力。

**改进方向**  
- 使用降采样后的缩略图做差异检测（如缩小到 1/8 再比较）
- 或直接复用 Image 的 byte buffer，不生成完整 Bitmap
- 如果不使用，删除未使用的类

---

### M8. 缺少统一日志系统

**问题所在**  
项目用 `MonitorDebugState.update()` 作为调试输出手段，这本质上是一个单例状态更新器，不是日志系统。没有分级日志（verbose/debug/info/warn/error），release 包无法关闭调试输出。

**改进方向**  
- 引入 Timber 日志框架
- Debug 版本打印 Logcat，Release 版本可选择性上报
- `MonitorDebugState` 保留用于 UI 展示的关键状态，但不替代日志

---

### M9. 没有 ProGuard / R8 混淆配置

**问题所在**  
build.gradle.kts 中没有看到 `buildTypes` 的 release 配置，不确定是否启用了混淆和代码压缩。

**改进方向**  
- 添加 release build type 配置：
  ```kotlin
  buildTypes {
      release {
          isMinifyEnabled = true
          isShrinkResources = true
          proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      }
  }
  ```
- 为 Room、ML Kit、Compose 等添加必要的 keep 规则

---

### M10. 硬编码字符串未放入 strings.xml

**问题所在**  
大量 UI 文本硬编码在 Kotlin 文件中，例如：
- `ScreenCaptureService.kt` 中所有悬浮窗文字（"识别结果"、"保存"、"重新识别"等）
- `MainActivity.kt` 中的各种提示文案

虽然这是个人项目，但硬编码不利于国际化和统一修改。

**改进方向**  
- 将所有用户可见的字符串移到 `res/values/strings.xml`
- Service 中通过 `context.getString(R.string.xxx)` 获取

---

## 低优先级（Low）

### L1. BallState 可考虑用 sealed class 携带状态数据

**问题所在**  
```kotlin
private enum class BallState { IDLE, SCANNING, SUCCESS, ERROR }
```

`enum` 无法携带关联数据。如果后续需要在 ERROR 状态下携带错误信息，或在 SUCCESS 状态下携带价格信息，enum 就不够用了。

**改进方向**  
改用 `sealed class`：
```kotlin
private sealed class BallState {
    data object Idle : BallState()
    data object Scanning : BallState()
    data class Success(val priceCents: Long) : BallState()
    data class Error(val message: String) : BallState()
}
```

---

### L2. 版本号硬编码

**问题所在**  
```kotlin
versionCode = 21
versionName = "0.8.8"
```

**改进方向**  
- 使用 `gradle.properties` 或 `buildSrc` 统一管理版本号
- 或从 git tag 自动生成版本号

---

### L3. TargetSdk 未升级到 35

**问题所在**  
`compileSdk = 35` 但 `targetSdk = 34`。虽然不紧急，但应尽快跟进最新 targetSdk。

**改进方向**  
- 升级 targetSdk 到 35
- 检查并适配 Android 15 行为变更

---

### L4. 缺少 KDoc / 公开 API 文档

**问题所在**  
公共类和函数大多缺少 KDoc 注释。内部代码注释详细（中文），但对外暴露的 API 缺少文档。

**改进方向**  
- 为 Repository、Dao、ViewModel 等核心类添加 KDoc
- 说明每个函数的用途、参数含义、返回值、可能的异常

---

### L5. 缺少 Baseline Profile 性能优化

**问题所在**  
没有使用 Baseline Profile 来优化 Compose 应用的启动性能和运行时性能。

**改进方向**  
- 生成 Baseline Profile
- 配置安装时预编译（`androidx.profileinstaller`）
- 可以显著提升 Compose UI 的首次渲染速度

---

### L6. 缺少 Compose Navigation 架构预留

**问题所在**  
目前只有一个 Activity + 一个 Screen，不需要导航。但如果后续增加设置页、详情页等，需要引入导航组件。

**改进方向**  
- 提前规划信息架构
- 在适当时机引入 `androidx.navigation:navigation-compose`

---

### L7. accessibility_service_config 中 flagReportViewIds 未被使用

**问题所在**  
```xml
android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews"
```

配置了 `flagReportViewIds` 和 `flagIncludeNotImportantViews`，但 `PddForegroundAccessibilityService` 只监听了 `TYPE_WINDOW_STATE_CHANGED` 来获取包名，并没有读取 View 树。这两个 flag 是不必要的。

**改进方向**  
- 移除未使用的 flag，减少不必要的系统开销
- 当前只需要检测前台应用，`TYPE_WINDOW_STATE_CHANGED` 事件已足够

---

### L8. ProductPriceComparison 未使用 previousPriceCents

**问题所在**  
```kotlin
data class ProductPriceComparison(
    val matchedTitle: String,
    val previousPriceCents: Long,  // 好像未被使用
    val previousLowestCents: Long
)
```

`previousPriceCents` 字段在比较逻辑中只用到了 `previousLowestCents`。如果确实没用可以删除，或者增加"比上次价格涨跌"的显示。

**改进方向**  
- 确认是否需要该字段，不需要则删除
- 或者在对比条中增加"比上次记录贵/便宜"的信息

---

### L9. parseListProducts 函数定义但未被调用

**问题所在**  
`ProductTextParser.kt` 第 530 行定义了 `parseListProducts` 函数（用于列表页多商品识别），但在 `parseWithReason` 中只调用了 `parseDetailProduct`。列表页解析功能似乎开发了但未启用。

**改进方向**  
- 如果是 WIP 功能，添加注释说明
- 如果不再需要，删除未使用的代码
- 或者增加配置项让用户选择是否启用列表页多商品识别

---

### L10. 缺少数据导出 / 备份功能

**问题所在**  
用户积累的价格历史数据只能存在本地，如果换手机或清除数据就会丢失。

**改进方向**  
- 添加数据导出为 JSON / CSV 的功能
- 或支持 Android Auto Backup（`allowBackup="true"` 已开启，但要测试 Room 数据库是否正确备份）

---

## 总结统计

| 优先级 | 数量 | 主要方向 |
|--------|------|----------|
| 高 | 8 | 架构拆分、性能瓶颈、内存安全、错误处理 |
| 中 | 10 | 代码组织、测试覆盖、细节优化 |
| 低 | 10 | 代码整洁、未来扩展、体验完善 |

**最值得优先做的三件事**：
1. **拆分 ScreenCaptureService** — 1000 行上帝类是维护成本最高的地方
2. **引入 Hilt 依赖注入** — 解决全局状态和可测试性问题
3. **优化 TitleMatcher 性能** — 数据量增长后最先遇到的性能瓶颈
