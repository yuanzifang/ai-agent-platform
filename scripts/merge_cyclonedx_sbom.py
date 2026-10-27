#!/usr/bin/env python3
"""Merge CycloneDX JSON SBOMs into one release-level BOM.

This intentionally keeps the merge conservative: components and dependencies
are copied through, deduplicated by purl/bom-ref, and vulnerabilities are
retained when present. It does not rewrite component identity or claims that
the merged BOM is a single build graph.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import uuid
from pathlib import Path


def component_key(component: dict) -> str:
    purl = component.get("purl")
    if purl:
        return f"purl:{purl}"
    return "fallback:{type}|{group}|{name}|{version}|{ref}".format(
        type=component.get("type", ""),
        group=component.get("group", ""),
        name=component.get("name", ""),
        version=component.get("version", ""),
        ref=component.get("bom-ref", ""),
    )


def dependency_key(dependency: dict) -> str:
    return str(dependency.get("ref") or dependency.get("bom-ref") or "")


def vulnerability_key(vulnerability: dict) -> str:
    return str(vulnerability.get("id") or vulnerability.get("bom-ref") or "")


def merge_tools(metadata: list[dict]) -> dict | None:
    merged: list[dict] = []
    seen: set[str] = set()
    for item in metadata:
        tools = item.get("tools")
        if isinstance(tools, dict):
            candidates = tools.get("components") or tools.get("services") or []
        elif isinstance(tools, list):
            candidates = tools
        else:
            continue
        for tool in candidates:
            key = json.dumps(tool, ensure_ascii=False, sort_keys=True)
            if key not in seen:
                seen.add(key)
                merged.append(tool)
    return {"components": merged} if merged else None


def merge(inputs: list[Path], output: Path) -> dict:
    if len(inputs) < 2:
        raise ValueError("至少需要两个输入 SBOM")

    boms = []
    for path in inputs:
        with path.open(encoding="utf-8") as fh:
            bom = json.load(fh)
        if bom.get("bomFormat") != "CycloneDX":
            raise ValueError(f"{path}: 不是 CycloneDX BOM")
        if not bom.get("components"):
            raise ValueError(f"{path}: components 为空")
        boms.append(bom)

    components: list[dict] = []
    component_keys: set[str] = set()
    dependencies: list[dict] = []
    dependency_map: dict[str, set[str]] = {}
    vulnerabilities: list[dict] = []
    vulnerability_keys: set[str] = set()

    for bom in boms:
        for component in bom.get("components") or []:
            key = component_key(component)
            if key in component_keys:
                continue
            component_keys.add(key)
            components.append(component)
        for dependency in bom.get("dependencies") or []:
            ref = dependency_key(dependency)
            if not ref:
                continue
            depends_on = dependency_map.setdefault(ref, set())
            depends_on.update(str(x) for x in dependency.get("dependsOn") or [])
        for vulnerability in bom.get("vulnerabilities") or []:
            key = vulnerability_key(vulnerability)
            if key and key in vulnerability_keys:
                continue
            if key:
                vulnerability_keys.add(key)
            vulnerabilities.append(vulnerability)

    for ref, depends_on in dependency_map.items():
        dependencies.append({"ref": ref, "dependsOn": sorted(depends_on)})

    metadata: dict = {
        "timestamp": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "component": {
            "type": "application",
            "bom-ref": "application:ai-agent-platform",
            "name": "ai-agent-platform",
            "version": "merged-release",
        },
        "properties": [
            {"name": "sbom.merge.sources", "value": ", ".join(str(p) for p in inputs)},
        ],
    }
    tools = merge_tools([bom.get("metadata") or {} for bom in boms])
    if tools:
        metadata["tools"] = tools

    merged = {
        "$schema": "http://cyclonedx.org/schema/bom-1.7.schema.json",
        "bomFormat": "CycloneDX",
        "specVersion": "1.7",
        "serialNumber": f"urn:uuid:{uuid.uuid4()}",
        "version": 1,
        "metadata": metadata,
        "components": components,
        "dependencies": dependencies,
    }
    if vulnerabilities:
        merged["vulnerabilities"] = vulnerabilities

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as fh:
        json.dump(merged, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    return merged


def main() -> int:
    parser = argparse.ArgumentParser(description="Merge CycloneDX JSON SBOMs")
    parser.add_argument("inputs", nargs="+", type=Path, help="输入 CycloneDX JSON")
    parser.add_argument("--output", required=True, type=Path, help="输出合并 BOM")
    args = parser.parse_args()
    try:
        merged = merge(args.inputs, args.output)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"merge-sbom: {exc}", file=sys.stderr)
        return 1
    print(
        f"merged {len(args.inputs)} SBOMs -> {args.output} "
        f"components={len(merged['components'])} vulnerabilities={len(merged.get('vulnerabilities', []))}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
