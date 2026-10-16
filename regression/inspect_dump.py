# -*- coding: utf-8 -*-
"""调试辅助：打印一份 dump 的全部 OCR 行（坐标+颜色比例+文本）与解析结果。"""
import json
import sys

fp = sys.argv[1]
d = json.load(open(fp, encoding="utf-8"))
r = d.get("result") or {}
print(f"screen {d.get('screenWidth')}x{d.get('screenHeight')}  "
      f"title={r.get('title')!r}  price={r.get('priceCents')}")
pr = d.get("priceRefine")
if pr:
    print(f"priceRefine: zoom={pr.get('zoomText')!r} first={pr.get('firstCents')}")
fpt = d.get("firstPassTitle")
if fpt:
    print(f"firstPassTitle: {fpt!r}")
print("-" * 100)
for l in d.get("lines", []):
    print(f"{l['left']:5} {l['top']:5} {l['right']:5} {l['bottom']:5} "
          f"d={l['dark']:.3f} li={l['light']:.3f} r={l['red']:.3f} | {l['text'][:48]}")
