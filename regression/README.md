# 回归基线设施

改 `ProductTextParser` 规则前的安全网：先同步真 parser → 用历史 dump 回放 → 与真机
记录对账，确认工具零差异后再谈改规则；改完重跑，差异即为改动的影响面。

## 组件

- `../replay/` — Gradle JVM 模块。`sync_from_app.sh` 把 app 里的真 parser **字节级**
  同步过来（仅放开 4 处可见性，校验行数），配 `android.graphics`/`mlkit.Text` 桩类。
  旧手抄版 parser（已漂移）归档在 `../replay/legacy/`。
- `gen_scenarios.py` — dump JSON → `replay/.../Scenarios.kt`（每场景一个函数，避免
  JVM 64KB 方法上限；文件在 .gitignore，随 dump 再生）。
- `score_baseline.py` — 不重跑 parser，用 dump 记录的输出对标注打质量分 → `BASELINE.md`。
- `compare_replay.py` — 重跑 parser（一遍解析层）并与真机输出对账，预期零差异。

## 标准流程（改规则时）

```bash
bash replay/sync_from_app.sh          # 1. 同步真 parser（改动后必须重跑）
python regression/gen_scenarios.py    # 2. dump → 场景（新录 dump 后）
python regression/compare_replay.py   # 3. 回放对账，必须零差异才可信
python regression/score_baseline.py   # 4. 质量分对照 BASELINE.md
```

## 已知保真度缺口

`OcrDumpWriter` 不记录 OCR 词块（`OcrLine.elements`），底栏价格的词块路径在回放中
退化为纯文本路径。`compare_replay.py` 里 `KNOWN_ELEMENT_LOSS` 白名单了 7 份受此影响
的场景（batch2 的 14%）。扩展 dump 格式后重录批次即可清空白名单——应与下一次改
App 规则同批进行（一次装机）。

## 环境注意（Windows）

- Kotlin 守护进程在非 ASCII 工程路径（`D:\开发\...`）上会挂：跑 replay 必须带
  `-Pkotlin.compiler.execution.strategy=in-process`（compare_replay.py 已内置）。
- Gradle 重定向输出默认 GBK：Runner 已强制 UTF-8 stdout。
