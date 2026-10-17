# 本地截图配置记价：GitHub 调研与落地要求

> 后续参考资料，暂不实施。当前任务以 `FLASH_CAPTURE_FINAL_HANDOFF.md` 为准：先完成“圆角方柱”双段悬浮按钮和独立高清截图；本地模型验证与接入推迟到后续需求明确后再做。

调研日期：2026-10-17。本文替代旧方案中“第一版优先云端”的选择。目标是手机内完成识图，不要求用户配置服务器、API 地址或密钥。

## 结论和验证边界

优先验证 **OpenBMB/MiniCPM-V-Apps v2.5 + MiniCPM-V 4.6 Q4_K_M**。理由是已经有官方组织下的 Android 安装包、模型文件、Kotlin 包装和图片推理 JNI 源码，可以从完整参考实现开始。

本次核实了公开文档、发布页、构建配置和关键源码；尚未在用户手机安装、编译或执行模型，也没有实测拼多多配置识别准确率。不能把“有可运行参考实现”表述为“用户手机已验证可用”。用户已确认目标手机为 **小米 17 Ultra，16 GB RAM**；还需实测系统版本、实际可用内存和原始规格弹窗截图样本。

### 已确定的目标手机

- 用户提供：小米 17 Ultra，16 GB 内存。
- [小米官方参数](https://www.mi.com/global/product/xiaomi-17-ultra/specs/)列出 Snapdragon 8 Elite Gen 5 平台；地区版本的其他差异不在本方案中假设，具体系统版本以手机实测为准。
- 适配判断：总内存高于参考项目对 4.6 的建议，值得直接进入设备验证；这不等于模型在本业务的准确率或后台稳定性已获验证。
- 第一轮保持 MiniCPM-V 4.6 Q4_K_M 和通用 CPU 路径，先验证离线准确性及连续处理，再决定是否优化加速。16 GB 内存不作为直接换到大模型的理由。
- 设备验收重点：拼多多与记价应用同时运行时的可用内存、冷启动及单条耗时、连续处理、切后台/锁屏后的任务恢复。依实际结果决定自动后台处理策略，不宣称该机型必定不会被系统终止后台任务。

在现有电脑环境尝试读取设备列表时，adb.exe 因执行权限失败，未取得设备信息。未改动手机或安装软件。

## 候选方案

| 方案 | 已核实的证据 | 本项目选择 |
| --- | --- | --- |
| [MiniCPM-V-Apps](https://github.com/OpenBMB/MiniCPM-V-Apps) | Android 发布包；Kotlin/JNI；GGUF 模型及视觉投影文件；支持手机离线图片理解 | 优先验证 |
| [阿里 MNN / MNN Chat](https://github.com/alibaba/MNN/blob/master/apps/Android/MnnLlmChat/README_CN.md) | Android 图片转文字应用；源码和构建步骤；支持 Qwen-VL 等视觉系列 | 备用；先用成品验证目标机，不能只看“框架支持”就选任意模型 |
| [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) | 有离线 Ask Image、Android 安装包和源码；当前应用要求 Android 12+ | 备用试验；不能直接当成支持本项目全部 Android 10+ 设备的可嵌入组件 |
| [llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md) | libmtmd 图片支持及 Android 构建资料 | 是可用底层，但直接从通用引擎开始需自行完成更多接线；优先借鉴前述 MiniCPM 完整实现 |

这是一份针对现有 Android 项目的适配判断，不是模型准确率排行榜。MNN 官方文档也明确没有保证所有低配置手机可用。第一版不选择 QNN/NPU 专用转换路径，避免额外的芯片绑定和模型转换工作。

## 优先方案的具体资产

- 应用源码：[OpenBMB/MiniCPM-V-Apps](https://github.com/OpenBMB/MiniCPM-V-Apps)。
- 固定版本：[v2.5 发布页](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/tag/v2.5)，发布提交短号 `cee7cae`；正式接入时记录完整提交号及其 submodule 提交，不跟随 main 自动升级。
- APK：[MiniCPM-V-demo-Android-v2.5.apk](https://github.com/OpenBMB/MiniCPM-V-Apps/releases/download/v2.5/MiniCPM-V-demo-Android-v2.5.apk)。发布说明约 20 MB，这是应用本身，**不含完整模型**。
- 安装要求：[官方安装说明](https://github.com/OpenBMB/MiniCPM-V-Apps/blob/main/DOWNLOAD.md) 声明 Android 8.0+、arm64-v8a，建议至少 6 GB RAM。满足这些条件仍不等于后台内存一定足够。
- 模型：[官方 GGUF 文件列表](https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/tree/main)。`MiniCPM-V-4_6-Q4_K_M.gguf` 约 529 MB，必须配套 `mmproj-model-f16.gguf` 约 1.11 GB，总下载约 1.64 GB。下载体积不是运行内存需求；实际峰值应在设备上测量。
- 国内下载入口：[官方 ModelScope 模型仓库](https://modelscope.cn/models/OpenBMB/MiniCPM-V-4.6-gguf)，来自项目文档；本次未测下载速度或所有文件可达性。

首选 4.6 是为固定一条已有发布包和代码的验证路线，不宣称它的商品截图准确率优于其他模型。先试这个版本，未达标再测试替代方案，不能无依据换到更大的模型。

## 已查看的实际接线代码

- [LlamaEngine.kt（v2.5）](https://github.com/OpenBMB/MiniCPM-V-Apps/blob/v2.5/MiniCPM-V-demo-Android/app/src/main/java/com/example/minicpm_v_demo/LlamaEngine.kt)：Android/Kotlin 包装。
- [llama_jni.cpp（v2.5）](https://github.com/OpenBMB/MiniCPM-V-Apps/blob/v2.5/MiniCPM-V-demo-Android/app/src/main/cpp/llama_jni.cpp)：包含模型加载、loadMmproj、图片编码处理、processUserPrompt、逐 token 生成、重置与释放。图片处理路径使用 mtmd，不是只有文字接口。
- [CMakeLists.txt（v2.5）](https://github.com/OpenBMB/MiniCPM-V-Apps/blob/v2.5/MiniCPM-V-demo-Android/app/src/main/cpp/CMakeLists.txt)：链接仓库固定的 llama.cpp-omni 子模块。
- [build.gradle.kts（v2.5）](https://github.com/OpenBMB/MiniCPM-V-Apps/blob/v2.5/MiniCPM-V-demo-Android/app/build.gradle.kts)：arm64 原生构建，NDK 27.0.12077973；该版本构建脚本实际指定 CMake 4.1.2。主 README 仍提到 3.22.1，实施时以固定版本的实际构建脚本为准。

注意：这是参考 App，不是一个加上 Maven 坐标就能调用的现成 SDK。需要整理成 Android library、JNI 和统一 Kotlin 接口。参考代码有全局 native 状态，必须串行调用并正确管理生命周期。改包名时同步修正 JNI 绑定，不能只复制 Kotlin 文件。

现有项目 AGP/Kotlin/SDK 配置与参考 App 不同；不要把对方完整应用及整个 Gradle 配置覆盖到本项目。先单独跑参考包，再提取推理最小依赖、适配构建；不把语音、聊天界面等无关功能一并引入。

## Flash 必须先完成的阶段 0

1. 记录目标手机的型号、Android 版本、CPU/ABI、RAM、可用存储和实际内存。没有设备时完成代码准备，报告设备验证缺项，不宣称实测成功。
2. 在目标机安装官方参考 APK，下载并选择 MiniCPM-V 4.6，核验两个模型文件。完成下载后断开网络验证图片推理。
3. 用原始拼多多规格截图测试，而不是自然风景或通用图片。先确认其能读取高亮选项及对应价格。只有 OCR JSON 或旧 720 像素附图时，不能替代完整的原图样本。
4. 对一张截图先要求简单字段输出：已选配置、页面显示价格、价格类型、证据原文；完整商品名来自同一条记录中明确补拍的详情图。
5. 若一条记录多图输入未被所选接口正确支持，第一版逐图独立提取，再按明确角色合并字段，并保留冲突。不要简单拼成长图导致小字进一步缩小，也不要依靠长聊天历史把不同商品混入上下文。
6. 记录冷启动时间、单条耗时、峰值内存、连续处理与后台恢复情况。没有测量不要承诺“几秒”“百 MB”或“所有手机都能跑”。

建议在这一步设置实际业务验收门槛：20 组人工标注样本中，配置和价格对应正确至少 19 组；缺失信息正确留空；不得将起价、划线价或优惠金额冒充选中配置价。这个门槛是项目建议，不是官方模型指标，也不证明对未来截图零错误。

若模型只能启动但截图任务未达标，结论应为“运行可行、业务识别尚未达标”，先改图像区域/提示词并复测，必要时对比 MNN 备用方案；不能直接开发完 UI 就交付“已能用”。

## 集成进记价 App 的方式

```text
用户截图 → 高清原图立即独立存档 → 持久队列
        → 手机本地视觉模型 → 结果校验 → 配置记录待确认
```

- 在主方案新增 `LocalVisionEngine`：模型检查、加载、图片分析、取消、释放；实现方法按已经验证的 Kotlin/JNI 调用封装。
- 模型放 `filesDir/models/<modelId>/`，用户截图继续放 `filesDir/sku_records/`，与旧 OCR 各自独立。模型单独下载或从系统文件选择器导入，不把约 1.64 GB 权重塞入 APK。
- 用户只看到“下载本地识图组件 / 下载进度 / 已就绪 / 开始整理”；没有 API 密钥、服务器地址、端口或命令行。
- 第一次下载需网络，随后截图处理离线；不能把“本地”误写成连首次模型获取也不需要网络。
- 下载和导入校验文件清单、长度和可信校验值，断点/中断可恢复，文件完整后才置为就绪；系统备份排除大型模型，截图/数据库按既定备份策略处理。
- 本地任务按模型缺失、内存不足、图片不可读、结构化结果不合法、用户取消分类处理，不沿用云端 401/429 重试逻辑。
- 使用持久队列，但不保证模型在 Android 后台无限运行。短任务可用普通 Worker；超过正常执行窗口时按系统要求使用带通知的长任务机制，或挂起后回前台继续；不要保持录屏服务来维持模型。
- 真正的 CPU/Native 运算取消需要引擎支持，不能仅取消 Kotlin 协程就声称已经停机。极端 native 崩溃/内存压力可考虑独立推理进程，必须通过 Binder 等正规接口调用，按真实复杂度决定，不要求第一版无条件增加。
- 每处理一条清理上一条上下文，防止把上一商品的标题、配置或价格带入下一条。单引擎、单次推理，空闲时释放模型，加载耗时与内存占用以实测选择平衡点。
- 保留原图和人工修改，识别失败仍可人工记价。结果不会写入普通 OCR 历史。

## 完成标准

分开交付三项证据：①目标机参考 App 离线识图；②嵌入当前 App 的同模型离线调用；③实际商品截图字段核对。只有三项都经过验证，才可表述为“此手机上已经实现并可用”。

给 Flash 的要求：先读本文，再按主方案实现；本文的本地选择优先于旧云端章节。不要只提供项目链接，不要只做一个空接口，不要让用户长期依赖另一个聊天 App 手工搬运结果。
