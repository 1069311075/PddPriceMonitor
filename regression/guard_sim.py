# -*- coding: utf-8 -*-
"""价格护栏规则模拟：在 50 份 batch2 dump 上预演 guardPriceMagnitude，
确认（a）两个错价案例被纠正（b）其余 42 个可比案例零误伤。"""
import json, glob, re, os, sys

RESTORE = re.compile(r"即将恢复\s*([0-9]+(?:[.,][0-9]{1,2})?)\s*元")
PREDISC = re.compile(r"优惠前\s*[¥￥]?\s*([0-9]+(?:[.,][0-9]{1,2})?)")
ORIGROUP = re.compile(r"原拼单价\s*[¥￥]?\s*([0-9]+(?:[.,][0-9]{1,2})?)")
SUBSIDY = re.compile(r"(?:官方|政府)(?:狂)?[补朴][贴貼貼]?\s*(?:下单)?\s*(?:立减)?\s*([0-9]+(?:[.,][0-9]{1,2})?)\s*元")


def yuan_to_cents(t):
    parts = t.replace(",", ".").split(".")
    yuan = int(parts[0])
    frac = (parts[1] if len(parts) > 1 else "").ljust(2, "0")[:2]
    return yuan * 100 + int(frac)


def candidates_of(cents):
    """cents 的数字串全位置插小数点候选 + 自身"""
    s = str(cents)
    if s.endswith("00"):
        s = s[:-2] if s[:-2] else s
    out = {cents}
    for i in range(1, len(s)):
        out.add(yuan_to_cents(s[:i] + "." + s[i:]))
    return out


def guard(lines, cents):
    text = " || ".join(l["text"] for l in lines)
    Rs = [yuan_to_cents(m) for m in RESTORE.findall(text)] + \
         [yuan_to_cents(m) for m in PREDISC.findall(text)] + \
         [yuan_to_cents(m) for m in ORIGROUP.findall(text)]
    R = min(Rs) if Rs else None
    S = max([yuan_to_cents(m) for m in SUBSIDY.findall(text)] or [None], default=None)

    t1 = R is not None and cents > R
    t2 = S is not None and S >= 2 * cents
    t3 = S is not None and cents > 0 and S / cents < 0.02
    if not (t1 or t2 or t3):
        return cents, None, (R, S)
    cands = candidates_of(cents)
    survivors = []
    for c in cands:
        if R is not None and c > R:
            continue
        if S is not None:
            ratio = S / c if c else 99
            if ratio < 0.05 or ratio > 0.9:
                continue
        survivors.append(c)
    pick = (min if t2 else max)(survivors) if survivors else None
    if pick == cents:
        pick = None
    return cents, pick, (R, S)


def main():
    dump_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join("ocr_dumps_local", "batch2")
    n_changed = 0
    for fp in sorted(glob.glob(os.path.join(dump_dir, "*.json"))):
        d = json.load(open(fp, encoding="utf-8"))
        r = d.get("result") or {}
        if not r.get("title"):
            continue
        pr = d.get("priceRefine") or {}
        first = pr.get("firstCents", r["priceCents"])
        orig, fixed, (R, S) = guard(d.get("lines", []), first)
        if fixed is not None:
            n_changed += 1
            print(f"FIX {d['timestamp']}  ¥{first/100} -> ¥{fixed/100}   R={R and R/100} S={S and S/100}")
        else:
            # 打印触发但未改的（安全网观察）
            text = " || ".join(l["text"] for l in d.get("lines", []))
            fired = (R is not None and first > R) or (S is not None and S >= 2 * first) or \
                    (S is not None and first > 0 and S / first < 0.02)
            if fired:
                print(f"TRIG-NOFIX {d['timestamp']}  ¥{first/100}  R={R and R/100} S={S and S/100}")
    print(f"\n改动 {n_changed} 处")


if __name__ == "__main__":
    main()
