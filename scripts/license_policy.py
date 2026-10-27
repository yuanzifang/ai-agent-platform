#!/usr/bin/env python3
"""依赖许可证策略：阻断 AGPL/SSPL/GPL-only/unknown；弱 copyleft 仅告警。

用法:
  python3 scripts/license_policy.py frontend-licenses.json backend/target/licenses/THIRD-PARTY.txt
"""
import json
import pathlib
import re
import sys

BLOCK = re.compile(r"\b(AGPL|SSPL|UNLICENSED|UNKNOWN|NOASSERTION)\b")
WARN = re.compile(r"\b(?:L?GPL|EPL|MPL|CDDL)-?\d?")


def frontend_licenses(path):
    lines = []
    data = json.loads(pathlib.Path(path).read_text())
    for result in data.get("Results", []):
        for lic in result.get("Licenses", []):
            lines.append(f"{lic.get('PkgName', '?')}: {lic.get('Name', '?')}")
    return lines


def backend_licenses(path):
    return [line.strip() for line in pathlib.Path(path).read_text().splitlines()
            if line.strip() and not line.strip().startswith("Lists of")]


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    lines = frontend_licenses(sys.argv[1]) + backend_licenses(sys.argv[2])
    blocked, warned = [], []
    for line in lines:
        upper = line.upper()
        if BLOCK.search(upper):
            # GPL + CPE / EPL / EDL 是双许可，不作为 GPL-only 阻断
            if "GPL" in upper and any(ok in upper for ok in ("CPE", "EPL", "EDL")):
                warned.append(line)
            else:
                blocked.append(line)
        elif WARN.search(upper):
            warned.append(line)
    print(f"licenses checked={len(lines)} warnings={len(warned)} blocked={len(blocked)}")
    for line in warned[:20]:
        print(f"WARN  {line}")
    for line in blocked:
        print(f"BLOCK {line}")
    if blocked:
        print("license policy failed: remove/replace blocked dependencies or record an explicit exception", file=sys.stderr)
        return 1
    print("license policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
