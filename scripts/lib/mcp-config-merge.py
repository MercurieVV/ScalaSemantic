#!/usr/bin/env python3
"""Idempotently merge one MCP server entry into a client config file.

Ported from the JSON/TOML/YAML merge logic in scalasemantic-mcp.scala so the
shell (.sh) launcher can reuse it instead of reimplementing bracket-matching
JSON parsing in POSIX sh. Only touches the one server entry named `server`;
every other key in the file is left untouched.

Usage:
    mcp-config-merge.py <json|toml|yaml> <target-file> <server-name> <argv-json> <extra-json>

    argv-json:  JSON array, e.g. '["scalasemantic-mcp.sh","serve","/proj","/cp.txt"]'
    extra-json: JSON object of extra fields merged into the JSON entry only
                (ignored for toml/yaml), e.g. '{"timeout": 60000}'
"""
import json
import sys
from pathlib import Path


def merge_json(existing: str, server: str, argv: list[str], extra: dict) -> str:
    try:
        doc = json.loads(existing) if existing.strip() else {}
    except json.JSONDecodeError:
        doc = {}
    if not isinstance(doc, dict):
        doc = {}
    servers = doc.setdefault("mcpServers", {})
    entry = {"command": argv[0], "args": argv[1:]}
    entry.update(extra)
    servers[server] = entry
    return json.dumps(doc, indent=2) + "\n"


def merge_toml(existing: str, server: str, argv: list[str]) -> str:
    args_toml = "[" + ", ".join(json.dumps(a) for a in argv[1:]) + "]"
    fresh = (
        f'[mcp_servers.{server}]\n'
        f'command = {json.dumps(argv[0])}\n'
        f'args = {args_toml}\n'
        f'startup_timeout_sec = 60\n'
        f'tool_timeout_sec = 60'
    )
    if not existing.strip():
        return fresh + "\n"
    header = f"[mcp_servers.{server}]"
    lines = existing.split("\n")
    try:
        idx = next(i for i, l in enumerate(lines) if l.strip() == header)
    except StopIteration:
        sep = "" if existing.endswith("\n") else "\n"
        return existing + sep + "\n" + fresh + "\n"
    end = next((i for i in range(idx + 1, len(lines)) if lines[i].strip().startswith("[")), len(lines))
    return "\n".join(lines[:idx] + fresh.split("\n") + lines[end:]).rstrip("\n") + "\n"


def continue_item(server: str, argv: list[str]) -> str:
    command, rest = argv[0], argv[1:]
    args = ""
    if rest:
        args = "\n    args:" + "".join(f"\n      - {json.dumps(a)}" for a in rest)
    return f'  - name: {json.dumps(server)}\n    command: {json.dumps(command)}{args}\n    connectionTimeout: 60000'


def merge_yaml(existing: str, server: str, argv: list[str]) -> str:
    item = continue_item(server, argv)
    fresh = (
        "name: ScalaSemantic MCP\n"
        "version: 1.0.0\n"
        "schema: v1\n"
        "mcpServers:\n" + item + "\n"
    )
    if not existing.strip():
        return fresh
    lines = existing.split("\n")
    try:
        ms_idx = next(i for i, l in enumerate(lines) if l.strip() == "mcpServers:")
    except StopIteration:
        sep = "" if existing.endswith("\n") else "\n"
        return existing + sep + "mcpServers:\n" + item + "\n"
    block_end = next(
        (i for i in range(ms_idx + 1, len(lines)) if lines[i].strip() and not lines[i].startswith(" ")),
        len(lines),
    )
    name_line = f"- name: {json.dumps(server)}"
    item_idx = next(
        (i for i in range(ms_idx + 1, block_end) if lines[i].strip() == name_line), None
    )
    if item_idx is None:
        out = lines[: ms_idx + 1] + item.split("\n") + lines[ms_idx + 1 :]
    else:
        indent = len(lines[item_idx]) - len(lines[item_idx].lstrip(" "))
        e = next(
            (
                i
                for i in range(item_idx + 1, block_end)
                if (len(lines[i]) - len(lines[i].lstrip(" "))) == indent and lines[i].strip().startswith("- ")
            ),
            block_end,
        )
        out = lines[:item_idx] + item.split("\n") + lines[e:]
    return "\n".join(out).rstrip("\n") + "\n"


def main() -> None:
    if len(sys.argv) != 6:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    fmt, target, server, argv_json, extra_json = sys.argv[1:]
    argv = json.loads(argv_json)
    extra = json.loads(extra_json)
    path = Path(target)
    path.parent.mkdir(parents=True, exist_ok=True)
    existing = path.read_text() if path.exists() else ""
    if fmt == "json":
        out = merge_json(existing, server, argv, extra)
    elif fmt == "toml":
        out = merge_toml(existing, server, argv)
    elif fmt == "yaml":
        out = merge_yaml(existing, server, argv)
    else:
        print(f"unknown format: {fmt}", file=sys.stderr)
        sys.exit(2)
    path.write_text(out)
    print(f"scalasemantic-mcp: wrote {path}", file=sys.stderr)


if __name__ == "__main__":
    main()
