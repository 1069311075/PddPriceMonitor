# -*- coding: utf-8 -*-
"""价格证据采集：每个 dump 的底栏候选行 + 中间带红价行 + 参照价行 + 入库价。
用于设计「价格护栏」规则——在改 parser 前先看清楚 50 案例的证据分布。"""
import json
import glob
import re
import sys

ROOT = r"d:\开发\pdd\PddPriceMonitor"
dump_dir = sys.argv[1] if len(sys.argv) > 1 else ROOT + r"\ocr_dumps_local\batch2"

REF = re.compile(r"(原拼单价|即将恢复|单独购买|划线|恢复\d+元|元起)")
H = 2464.0


def main():
    for fp in sorted(glob.glob(dump_dir + r"\*.json")):
        d = json.load(open(fp, encoding="utf-8"))
        r = d.get("result") or {}
        if not r.get("title"):
            continue
        cents = r.get("priceCents")
        pr = d.get("priceRefine") or {}
        zoom = (pr.get("zoomText") or "")[:30]
        # 底栏区（top >= 0.89H 或 bottom >= 0.94H）的行
        bottom = [l for l in d.get("lines", [])
                  if l["top"] >= 0.88 * H or l["bottom"] >= 0.94 * H]
        btxt = " || ".join(f"{l['text'][:26]}" for l in bottom if re.search(r"\d", l["text"]))
        # 中间带红价行 (0.44H-0.52H)
        mid = [l for l in d.get("lines", [])
               if 0.44 * H <= l["top"] <= 0.52 * H and l["red"] > 0.3]
        mtxt = " || ".join(f"{l['text'][:30]}" for l in mid)
        # 参照价行（全页找）
        refs = [l for l in d.get("lines", []) if REF.search(l["text"])]
        rtxt = " || ".join(f"{l['text'][:34]}" for l in refs)
        print(f"== {d['timestamp']}  存¥{cents/100:.2f}  zoom={zoom!r}")
        if btxt:
            print(f"   底栏: {btxt[:110]}")
        if mtxt:
            print(f"   中带: {mtxt[:110]}")
        if rtxt:
            print(f"   参照: {rtxt[:110]}")


main()
