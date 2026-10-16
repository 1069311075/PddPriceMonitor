# -*- coding: utf-8 -*-
"""
基线打分：用 dump 中记录的 parser 输出（标题/价格/titleRect）对比标注，
产出当前版本在不重跑 parser 前提下的质量基线。改规则后重跑的 harness 是另一件事。

用法: python regression/score_baseline.py
输入: ocr_dumps_local/batch2/*.json (2026-09-08 23:47 批次)
      ocr_dumps_local/labels_batch2.json (标注: vision 硬标 / 垃圾标 / 行数据级)
      h19 的证据在 DB+截图（其 dump 已被 50 份上限轮转掉），作为已知错价计入。
输出: regression/BASELINE.md
"""
import json
import glob
import os
import re
import urllib.request

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
DUMPS = os.path.join(ROOT, "ocr_dumps_local", "batch2")
LABELS = os.path.join(ROOT, "ocr_dumps_local", "labels_batch2.json")
OUT = os.path.join(os.path.dirname(__file__), "BASELINE.md")

# h19 米家电煮锅: 屏显 ¥111.9 (划线¥137, 视觉确认), 自动保存成 11.19
EXTRA_KNOWN_WRONG_PRICE = {"record": "h19", "ocr": 1119, "truth": 11190,
                           "note": "dump 已轮转，证据=DB history + h19.jpg 视觉"}

# 本批次确认出现过的繁体/异体字（非穷举，基线探针用）
TRAD_CHARS = "蓮遠鮮預釀橋裝軟漬練儲晶題滙"
PREFIX_JUNK = re.compile(r"(专卖店|店铺热销|^爆款|热銷\d*\+*件|\d+\+*人拼单|\d+小时|达人秀)")
SUFFIX_JUNK = re.compile(r"(等\d+项|不支持.*$|预售|天内发货|发货秒退|拼单即将结束?\s*$|V\s*!\s*$)")


def load_all():
    dumps = []
    for fp in sorted(glob.glob(os.path.join(DUMPS, "*.json"))):
        dumps.append(json.load(open(fp, encoding="utf-8")))
    labels = json.load(open(LABELS, encoding="utf-8"))
    return dumps, labels


def main():
    dumps, labels = json.loads("[]"), []
    dumps, labels = load_all()
    by_ts = {l["dump"]: l for l in labels}

    n = len(dumps)
    rejected = [d for d in dumps if not (d.get("result") and d["result"].get("title"))]
    ok = [d for d in dumps if d.get("result") and d["result"].get("title")]
    junk = [d for d in ok if by_ts.get(d["timestamp"], {}).get("expectReject")]
    real = [d for d in ok if d not in junk]

    # ---- 价格 ----
    known_wrong = []
    for d in real:
        l = by_ts.get(d["timestamp"], {})
        ep = l.get("expectPrice")
        if ep is not None and d["result"]["priceCents"] != ep:
            known_wrong.append((l.get("record"), d["result"]["priceCents"], ep))
    known_wrong.append((EXTRA_KNOWN_WRONG_PRICE["record"],
                        EXTRA_KNOWN_WRONG_PRICE["ocr"], EXTRA_KNOWN_WRONG_PRICE["truth"]))

    decimal_prices, int_risk = [], []
    for d in real:
        cents = d["result"]["priceCents"]
        pr = d.get("priceRefine") or {}
        zoom_dec = any(c.get("hasDecimal") for c in pr.get("candidates", []))
        if cents % 100 != 0 or pr.get("firstHasDecimal") or zoom_dec:
            decimal_prices.append(d)
        else:
            int_risk.append((d["timestamp"], cents / 100, (pr.get("zoomRawText") or "")[:20]))

    rescued = [d for d in real if (d.get("priceRefine") or {}).get("applied")]

    # ---- 标题 ----
    prefix_hits = [d for d in real if PREFIX_JUNK.search(d["result"]["title"])]
    suffix_hits = [d for d in real if SUFFIX_JUNK.search(d["result"]["title"])]
    trad_hits = [d for d in real if any(ch in d["result"]["title"] for ch in TRAD_CHARS)]

    # ---- 报告 ----
    lines = []
    a = lines.append
    a("# OCR 质量基线 — batch2 (2026-09-08 23:47–23:51, 50 dumps)\n")
    a("生成方式：dump 内记录的 parser 输出 vs 标注，未重跑 parser。\n")
    a(f"- 捕获总数 **{n}**：正常商品 **{len(real)}**，垃圾页误判为商品 **{len(junk)}**（"
      f"优惠券弹窗×4、用户评价×1），正确拒绝 **{len(rejected)}**")
    a(f"- 垃圾闸门漏放率 **{len(junk)/(len(junk)+len(rejected)):.0%}**（应拒 6，实拒 1）\n")
    a("## 价格\n")
    a(f"- 已确认错误 **{len(known_wrong)}**（视觉核验，全部为小数点丢失的量级错误）：")
    for rec, got, exp in known_wrong:
        a(f"  - {rec}: 入库 ¥{got/100:.2f} → 实际 ¥{exp/100:.2f}")
    a(f"- 小数点在场的价格（decimal 可见，视为正确）**{len(decimal_prices)}/{len(real)}**")
    a(f"- 价格二遍精识别生效（救回）**{len(rescued)}** 例")
    a(f"- 无小数点整数价格（现有机制无法验证的风险池，本轮已知 3 错）**{len(int_risk)}** 例：")
    for ts, v, zoom in int_risk:
        a(f"  - {ts} ¥{v:.2f} zoom='{zoom}'")
    a("")
    a("## 标题\n")
    a(f"- 前缀污染 **{len(prefix_hits)}** 例：")
    for d in prefix_hits:
        a(f"  - {d['timestamp']} {d['result']['title'][:30]}")
    a(f"- 后缀徽章污染 **{len(suffix_hits)}** 例：")
    for d in suffix_hits:
        a(f"  - {d['timestamp']} …{d['result']['title'][-24:]}")
    a(f"- 繁体/异体字命中 **{len(trad_hits)}** 例")
    a("- 垃圾标题已入库 3 条记录：h86/h87（优惠券）、h91（评价）")
    a("- 截断（titleRect 边界）：h76『低溫风』、h80『休闲零』等")
    a("")
    a("## 匹配/去重（来自 DB，同因 OCR 变体）\n")
    a("- h36/h38/h39：同一小米 NAS 存 3 条（存儲/存储/女档 三种读法）")
    a("- h20/h21/h23/h24：米家电蒸锅 S1 13L 至少 3 条重复")
    a("- 对照组：h8/h19 电煮锅两条不同链接正确归并为同一条（h19）\n")
    a("## 修复优先级映射\n")
    a("1. 价格护栏（划线价交叉验证）→ 直接消灭 3 个量级错误")
    a("2. 垃圾闸门（关注券/评价特征）→ 消灭 5/50 垃圾捕获")
    a("3. 前后缀剥离 → 9+ 例标题污染")
    a("4. 繁体降级+词典 → 8 例标题用字")
    a("5. 匹配层繁简归一 → NAS/电蒸锅三胞胎")

    text = "\n".join(lines)
    open(OUT, "w", encoding="utf-8").write(text)
    print(text)
    print(f"\nwritten: {OUT}")


if __name__ == "__main__":
    main()
