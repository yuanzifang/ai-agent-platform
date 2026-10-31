#!/usr/bin/env python3
"""校验后端中文/英文 messages key 完全一致。"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
ZH = ROOT / "backend/src/main/resources/i18n/messages.properties"
EN = ROOT / "backend/src/main/resources/i18n/messages_en.properties"


def keys(path: Path) -> set[str]:
    result = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        result.add(line.split("=", 1)[0].strip())
    return result


def main() -> int:
    zh, en = keys(ZH), keys(EN)
    print(f"backend i18n keys: zh={len(zh)}, en={len(en)}")
    missing_en = sorted(zh - en)
    missing_zh = sorted(en - zh)
    if missing_en or missing_zh:
        if missing_en:
            print("missing in messages_en.properties:\n" + "\n".join(missing_en), file=sys.stderr)
        if missing_zh:
            print("missing in messages.properties:\n" + "\n".join(missing_zh), file=sys.stderr)
        return 1
    print("backend i18n key parity OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
