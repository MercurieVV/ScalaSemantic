#!/usr/bin/env python3
"""PostToolUse hook: append one JSONL record per Claude tool call that touches Scala code.

Reads the hook payload (JSON) on stdin. Keeps only interactions that involve a `.scala` file:
file ops by `file_path`, Grep/Glob by pattern/glob/path mentioning "scala", Bash by `.scala` in the
command. Writes to $SCALA_INTERACTION_LOG (default ~/.claude/scala-interactions.jsonl). Never blocks
or fails the tool — any error just exits 0 with nothing logged.

See docs/CLAUDE_INTERACTION_STUDY.md for how to collect and analyse these logs.
"""
import sys, os, json, datetime


def main() -> None:
    try:
        d = json.load(sys.stdin)
    except Exception:
        return
    tool = d.get("tool_name", "")
    inp = d.get("tool_input", {}) or {}
    cwd = d.get("cwd", "")

    def target():
        fp = inp.get("file_path", "")
        if tool in ("Read", "Edit", "Write", "MultiEdit", "NotebookEdit"):
            return fp if isinstance(fp, str) and fp.endswith(".scala") else None
        if tool in ("Grep", "Glob"):
            blob = " ".join(str(inp.get(k, "")) for k in ("pattern", "glob", "path"))
            return blob.strip() if "scala" in blob.lower() else None
        if tool == "Bash":
            cmd = inp.get("command", "")
            return cmd if isinstance(cmd, str) and ".scala" in cmd else None
        return None

    t = target()
    if not t:
        return
    op = {"Read": "read", "Grep": "search", "Glob": "search", "Bash": "bash"}.get(tool, "write")
    rec = {
        "ts": datetime.datetime.now().isoformat(timespec="seconds"),
        "tool": tool,
        "op": op,
        "target": t[:500],
        "cwd": cwd,
    }
    log = os.environ.get(
        "SCALA_INTERACTION_LOG", os.path.expanduser("~/.claude/scala-interactions.jsonl")
    )
    os.makedirs(os.path.dirname(log), exist_ok=True)
    with open(log, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec) + "\n")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
    sys.exit(0)
