# -*- coding: utf-8 -*-
import sqlite3
import datetime
import sys

db = sqlite3.connect(r"d:\开发\pdd\PddPriceMonitor\ocr_dumps_local\pdd_price_monitor.db")
tables = [t[0] for t in db.execute("SELECT name FROM sqlite_master WHERE type='table'")]
print("tables:", tables)
for t in tables:
    cols = [c[1] for c in db.execute(f"PRAGMA table_info({t})")]
    print(t, cols)
    break
# 查 batch2 时段（2026-09-08 23:47 之后）的记录
rows = db.execute(
    "SELECT id, title, priceCents, recordedAt FROM product_price_history "
    "WHERE recordedAt > 1759348000000 ORDER BY id"
).fetchall()
for r in rows:
    print(r[0], datetime.datetime.fromtimestamp(r[3] / 1000).strftime("%m-%d %H:%M:%S"),
          r[1][:32], r[2])
