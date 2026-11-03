#!/usr/bin/env python3
"""Docs 生命周期门禁：状态入口、归档索引和根目录批次文件检查。"""
from pathlib import Path
import fnmatch
import sys

root = Path('docs')
required = [
    'README.md',
    '交付现状.md',
    '上线差距清单.md',
    'P0验收追踪矩阵.md',
    '技术决策记录.md',
    '故障演练记录.md',
    'archive/README.md',
]
missing = [name for name in required if not (root / name).is_file()]
if missing:
    print('docs check FAILED: missing required docs: ' + ', '.join(missing), file=sys.stderr)
    sys.exit(1)

forbidden = ['*实跑验收.md', '*长稳验收.md', '*上一版本回归.md', '*百文档基准.md']
bad = []
for path in root.iterdir():
    if path.is_file() and path.suffix == '.md':
        if any(fnmatch.fnmatch(path.name, pattern) for pattern in forbidden):
            bad.append(path.name)
if bad:
    print('docs check FAILED: archive batch docs at docs/archive/: ' + ', '.join(sorted(bad)), file=sys.stderr)
    sys.exit(1)
print('docs check OK')
