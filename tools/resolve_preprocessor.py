"""Resolve Deftu preprocessor directives for a single Minecraft version.

The multi-version toolkit normally rewrites `//#if MC >= X` blocks before
compiling. Its comparison of dotted versions (26.2 vs 26_02_00) does not
match here, so every `//$$` branch stays commented out and the `//#else`
branch is compiled instead. We only ship one MC version, so resolve the
directives once, deterministically, and keep plain sources.

Usage: python tools/resolve_preprocessor.py [MC_VERSION]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"

COND_RE = re.compile(r"^\s*//#if\s+(.+?)\s*$")
ELSE_RE = re.compile(r"^\s*//#else\s*$")
ENDIF_RE = re.compile(r"^\s*//#endif\s*$")
DISABLED_RE = re.compile(r"^(\s*)//\$\$ ?(.*)$")
MC_CMP_RE = re.compile(r"^MC\s*(>=|<=|>|<|==)\s*([\d.]+)$")


def evaluate(cond: str, mc: float) -> bool:
    m = MC_CMP_RE.match(cond.strip())
    if not m:
        raise ValueError(f"unsupported preprocessor condition: {cond!r}")
    op, raw = m.groups()
    version = float(raw)
    return {
        ">=": version <= mc,
        "<=": version >= mc,
        ">": version < mc,
        "<": version > mc,
        "==": version == mc,
    }[op]


def resolve(text: str, mc: float) -> str:
    out: list[str] = []
    stack: list[dict] = []

    def active() -> bool:
        return all(frame["take"] for frame in stack)

    for line in text.splitlines(keepends=True):
        if COND_RE.match(line):
            cond = COND_RE.match(line).group(1)
            stack.append({"take": evaluate(cond, mc), "seen_else": False})
            continue
        if ELSE_RE.match(line):
            if not stack:
                raise ValueError("//#else without //#if")
            stack[-1]["take"] = not stack[-1]["take"]
            stack[-1]["seen_else"] = True
            continue
        if ENDIF_RE.match(line):
            if not stack:
                raise ValueError("//#endif without //#if")
            stack.pop()
            continue

        disabled = DISABLED_RE.match(line)
        if disabled:
            if active():
                out.append(f"{disabled.group(1)}{disabled.group(2)}\n")
            continue

        if active():
            out.append(line)

    if stack:
        raise ValueError("unterminated //#if block")
    return "".join(out)


def main() -> int:
    mc = float(sys.argv[1]) if len(sys.argv) > 1 else 26.2
    changed = 0
    for path in sorted(SRC.rglob("*")):
        if not path.is_file() or path.suffix not in {".kt", ".java"}:
            continue
        text = path.read_text(encoding="utf-8")
        if "//#" not in text and "//$$" not in text:
            continue
        resolved = resolve(text, mc)
        if resolved != text:
            path.write_text(resolved, encoding="utf-8")
            changed += 1
            print(f"resolved {path.relative_to(ROOT)}")
    print(f"\n{changed} file(s) rewritten for MC {mc}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
