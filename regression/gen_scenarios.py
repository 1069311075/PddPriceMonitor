# -*- coding: utf-8 -*-
"""
dump JSON -> replay/.../Scenarios.kt（无依赖的 Kotlin 数据字面量，Runner 直接引用）。
重新录制了转储后重跑本脚本再跑 :replay:run 即可。

用法: python regression/gen_scenarios.py [dump_dir]
默认 dump_dir = ocr_dumps_local/batch2
"""
import json
import glob
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "replay", "src", "main", "kotlin",
                   "com", "example", "pddpricemonitor", "replay", "Scenarios.kt")


def esc(s: str) -> str:
    out = []
    for ch in s:
        if ch == '\\':
            out.append('\\\\')
        elif ch == '"':
            out.append('\\"')
        elif ch == '$':
            out.append('\\$')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\r':
            out.append('\\r')
        elif ch == '\t':
            out.append('\\t')
        elif ord(ch) < 0x20:
            out.append('\\u%04x' % ord(ch))
        else:
            out.append(ch)
    return ''.join(out)


def kdouble(v) -> str:
    """JSON number -> Kotlin Double 字面量（0 -> 0.0，保住类型）"""
    return f"{float(v)!r}"


def main():
    dump_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "ocr_dumps_local", "batch2")
    files = sorted(glob.glob(os.path.join(dump_dir, "*.json")))

    # 每个场景独立函数：单个 listOf 的 <clinit> 会超 JVM 64KB 方法上限
    parts = [
        "// 由 regression/gen_scenarios.py 生成，勿手改（源: %s）" % os.path.basename(dump_dir),
        "package com.example.pddpricemonitor.replay",
        "",
        "data class SElement(val text: String, val l: Int, val t: Int, val r: Int, val b: Int)",
        "data class SLine(val text: String, val l: Int, val t: Int, val r: Int, val b: Int,",
        "                val d: Double, val li: Double, val red: Double, val g: Double,",
        "                val els: List<SElement>)",
        "data class Scenario(val ts: String, val w: Int, val h: Int, val lines: List<SLine>)",
        "",
    ]
    names = []
    for idx, fp in enumerate(files):
        d = json.load(open(fp, encoding="utf-8"))
        w, h = d.get("screenWidth", 0), d.get("screenHeight", 0)
        ls = []
        for l in d.get("lines", []):
            els = ', '.join(
                f'SElement("{esc(e["text"])}", {e["left"]}, {e["top"]}, {e["right"]}, {e["bottom"]})'
                for e in l.get("elements", [])
            )
            ls.append(
                f'SLine("{esc(l["text"])}", {l["left"]}, {l["top"]}, {l["right"]}, {l["bottom"]}, '
                f'{kdouble(l["dark"])}, {kdouble(l["light"])}, {kdouble(l["red"])}, {kdouble(l["green"])}, '
                f'listOf({els}))'
            )
        name = f"s{idx}"
        names.append(name)
        parts.append(f"private fun {name}() = Scenario(\"{d['timestamp']}\", {w}, {h}, listOf(")
        for i in range(0, len(ls), 3):
            parts.append('        ' + ', '.join(ls[i:i + 3]) + ',')
        parts.append('    ))')
        parts.append('')
    parts.append("val scenarios: List<Scenario> = listOf(" + ', '.join(n + "()" for n in names) + ")")
    open(OUT, 'w', encoding='utf-8').write('\n'.join(parts) + '\n')
    print(f"{len(files)} scenarios -> {OUT}")


if __name__ == "__main__":
    main()
