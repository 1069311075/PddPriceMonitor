# -*- coding: utf-8 -*-
"""
跑 :replay:run 并把回放输出与真机 dump 记录对账。

对账目标（都是"一遍解析"层面）：
- 标题: dump.firstPassTitle（发生过二遍精识别时）否则 dump.result.title
- 价格: dump.priceRefine.firstCents（二遍修正过）否则 dump.result.priceCents
- 跳过: result 为空时预期 SKIP

预期零差异；出现差异 = 回放链路与真机行为有缝（同步漂移/桩缺陷/平台差异），
先修工具再谈改规则。

用法: python regression/compare_replay.py [dump_dir]
"""
import json
import glob
import os
import subprocess
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))

# 转储保真度缺口：OcrDumpWriter 不记录 OCR 词块（elements），底栏价格的词块路径
# 在回放中退化为纯文本路径。以下场景因此与真机不一致——不是回放 bug，也不是规则 bug。
# dump 格式已扩展记录 elements（OcrElementDump），重新录制转储后此白名单应清空。
KNOWN_ELEMENT_LOSS = {
    "20260908_234744_613",  # 柚子：真机经词块路径取到价
    "20260908_234902_029",  # 玉米：同上
    "20260908_234948_324",  # 舒肤佳：词块选中 32.49，文本路径得 5.0
    "20260908_234955_333",  # 搓背神器：词块选中 38.16
    "20260908_235002_482",  # 鸡尾酒：词块路径出价，回放 SKIP
    "20260908_235005_460",  # 鸡尾酒：同上
    "20260908_235057_100",  # 夹克：词块价格 169.15 → 标题锚点随之不同
}

# 2026-09-09 规则修复后的正确预期（覆盖 dump 里记录的旧错值）：
# - 价格量级护栏（guardPriceMagnitude）：参照价/补贴仲裁，修两个小数点丢失的量级错价
# - 券横幅标题修复（关注券/无门槛 + 旗舰店徽章前缀）：4 份券横幅页标题从横幅文案
#   落回真商品名（Art Master 音响）
# - 评价页闸门（该商品所属）：评价页整页拒绝
# 标题为当前回放输出原文（同页 4 份 dump 是不同帧的 OCR 变体，各自记录）。
# 真机核验：椰青 ¥78.77（划线 99.9−补贴21.13）、浴巾 ¥88.4（划线 ¥129）。
EXPECTED_FIXES = {
    "20260908_234815_305": ("【商超品质】佳沃泰国进口香水椰責9个大果椰子当季新鲜水果", 7877),
    "20260908_234937_609": ("日本hoyo套头浴巾柔軟吸水速干浴裙抗菌不易掉毛男女洗澡成人浴衣", 8840),
    "20260908_235024_691": ("Art Master术大师At S歇词音箱蓝篮牙黑胶唱片", 491000),
    "20260908_235027_076": ("Art Master术大师At S歇词音箱蓝篮牙黑胶唱片", 491000),
    "20260908_235029_665": ("At Master 艺术大师At S歌词音箱蓝牙黑胶唱片", 491000),
    "20260908_235034_960": ("Art Master 艺术大师Art S歌词音箱蓝牙黑跤唱片", 491000),
    "20260908_235049_971": ("SKIP", None),
}


def expected(d):
    r = d.get("result")
    if not (r and r.get("title")):
        return ("SKIP", None)
    title = d.get("firstPassTitle") or r["title"]
    pr = d.get("priceRefine") or {}
    cents = pr.get("firstCents", r["priceCents"])
    return (title, cents)


def main():
    dump_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "ocr_dumps_local", "batch2")
    dumps = {d["timestamp"]: d for d in
             (json.load(open(fp, encoding="utf-8")) for fp in sorted(glob.glob(os.path.join(dump_dir, "*.json"))))}

    r = subprocess.run(
        ["cmd", "/c", "gradlew.bat", ":replay:run", "--console=plain", "-q",
         # Kotlin 守护进程在非 ASCII 工程路径（D:\开发\...）上会挂，用进程内编译绕过
         "-Pkotlin.compiler.execution.strategy=in-process"],
        cwd=ROOT, capture_output=True, timeout=600)
    # Gradle 控制台输出是平台本地编码（中文 Windows = GBK），JSON/源码是 UTF-8，别混
    stdout = r.stdout.decode("utf-8", errors="replace") if isinstance(r.stdout, bytes) else r.stdout
    stderr = r.stderr.decode("utf-8", errors="replace") if isinstance(r.stderr, bytes) else r.stderr
    out = [ln for ln in stdout.splitlines() if "\t" in ln]
    if r.returncode != 0 or not out:
        print("gradle failed:", r.returncode)
        print(stdout[-2000:])
        print(stderr[-2000:])
        sys.exit(1)

    n_title = n_price = n_skip = n_known = 0
    mismatches = []
    for ln in out:
        ts, status, title, cents = (ln.split("\t") + ["", ""])[:4]
        d = dumps.get(ts)
        if d is None:
            mismatches.append((ts, "?", "回放多出未知时间戳"))
            continue
        if ts in KNOWN_ELEMENT_LOSS:
            n_known += 1  # 已知失真场景只要求"别崩出未知异常"，不比对具体值
            continue
        exp_title, exp_cents = EXPECTED_FIXES.get(ts) or expected(d)
        if status == "SKIP":
            n_skip += 1
            if exp_title != "SKIP":
                mismatches.append((ts, "SKIP", f"真机出了商品 {exp_title[:20]}"))
        else:
            if exp_title == "SKIP":
                mismatches.append((ts, "OK", "真机是 SKIP，回放出商品"))
                continue
            if title == exp_title:
                n_title += 1
            else:
                mismatches.append((ts, "标题", f"回放[{title[:28]}] 真机[{exp_title[:28]}]"))
            if int(cents) == exp_cents:
                n_price += 1
            else:
                mismatches.append((ts, "价格", f"回放¥{int(cents)/100} 真机¥{exp_cents/100}"))

    total_ok = sum(1 for ln in out if "\tOK\t" in ln)
    print(f"回放 {len(out)} 份：商品 {total_ok} / 跳过 {n_skip}")
    print(f"标题一致 {n_title}/{total_ok}，价格一致 {n_price}/{total_ok}，"
          f"已知失真 {n_known} 份，修复预期 {len(EXPECTED_FIXES)} 份")
    if mismatches:
        print(f"\n差异 {len(mismatches)} 处（先怀疑工具，再怀疑代码）：")
        for ts, kind, msg in mismatches:
            print(f"  {ts} [{kind}] {msg}")
        sys.exit(2)
    print("\n零差异 ✓ 回放链路与真机一遍解析行为一致")


if __name__ == "__main__":
    main()
