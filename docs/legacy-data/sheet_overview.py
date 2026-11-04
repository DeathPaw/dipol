#!/usr/bin/env python3
"""Быстрый обзор структуры выгруженных Google-таблиц (legacy-данные Диполь)."""
import sys
from openpyxl import load_workbook

for path in sys.argv[1:]:
    wb = load_workbook(path, read_only=True, data_only=True)
    print("=" * 100)
    print("FILE:", path)
    print("SHEETS:", len(wb.sheetnames))
    for name in wb.sheetnames:
        ws = wb[name]
        print("-" * 100)
        try:
            dims = ws.calculate_dimension()
        except ValueError:
            dims = ws.calculate_dimension(force=True)
        print(f"  SHEET «{name}»  dims={dims}  rows={ws.max_row} cols={ws.max_column}")
        rows = []
        for i, row in enumerate(ws.iter_rows(min_row=1, max_row=6, max_col=min(ws.max_column or 1, 40), values_only=True)):
            rows.append(row)
            if i >= 5:
                break
        for i, row in enumerate(rows, 1):
            cells = ["" if c is None else str(c).replace("\n", " ")[:28] for c in row]
            while cells and cells[-1] == "":
                cells.pop()
            if cells:
                print(f"    r{i}: " + " | ".join(cells))
    wb.close()
