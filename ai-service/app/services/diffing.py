"""Unified diff between two versions of a source tree.

The drift detector needs to show the LLM *what changed*, not the full
"before" and "after" source. A unified diff is both far cheaper in
tokens and far more precise: it points at the exact lines that moved.

The implementation uses Python's built-in :mod:`difflib` so the service
keeps its zero-native-dependency footprint.
"""

from __future__ import annotations

import difflib
from typing import Iterable, List, Tuple

#: Maximum number of diff lines sent to the LLM. Beyond this the prompt
#: becomes too expensive and the model starts losing focus.
MAX_DIFF_LINES = 2000


def unified_diff(files_before: Iterable[Tuple[str, str]],
                 files_after: Iterable[Tuple[str, str]],
                 *,
                 context: int = 3,
                 max_lines: int = MAX_DIFF_LINES) -> str:
    """Build a unified diff between two ``(path, content)`` collections.

    Files present in only one side are reported as a full add/remove so
    the LLM sees the whole file, not just a fragment.
    """

    before_map = {path: content for path, content in files_before}
    after_map = {path: content for path, content in files_after}

    all_paths = sorted(set(before_map) | set(after_map))

    chunks: List[str] = []
    for path in all_paths:
        old = before_map.get(path)
        new = after_map.get(path)

        if old is None:
            chunks.append(f"--- /dev/null\n+++ {path}\n@@ new file @@\n{_prefix_lines(new, '+')}")
            continue
        if new is None:
            chunks.append(f"--- {path}\n+++ /dev/null\n@@ deleted file @@\n{_prefix_lines(old, '-')}")
            continue
        if old == new:
            continue

        diff = difflib.unified_diff(
            old.splitlines(keepends=True),
            new.splitlines(keepends=True),
            fromfile=path,
            tofile=path,
            n=context,
        )
        body = "".join(diff)
        if body:
            chunks.append(body)

    if not chunks:
        return ""

    text = "\n".join(chunks)
    lines = text.splitlines()
    if len(lines) > max_lines:
        truncated = "\n".join(lines[:max_lines])
        return truncated + (
            f"\n... [diff truncated at {max_lines} lines; "
            f"{len(lines) - max_lines} more lines omitted]"
        )
    return text


def _prefix_lines(content: str | None, prefix: str) -> str:
    if not content:
        return ""
    return "".join(f"{prefix}{line}\n" for line in content.splitlines())
