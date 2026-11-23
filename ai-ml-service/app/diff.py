"""Pure line-hash comparison module for slowdown run regression highlighting (US-69, FR37.3, Sprint 5).

User Story: US-69 — Highlight Changed Lines in a Slowdown Run.
Traceability: FR37.3 · US-69 · EPIC 4 · Sprint 5.

Invariants (NFR8.2, AC2, Privacy Rules):
- NEVER stores, logs, or returns raw source code strings.
- Operates strictly on standard-library cryptographic hashes (SHA-256) of code lines.
- Pure and deterministic: identical inputs always yield identical outputs.
- Returns 1-based line numbers referring to the CURRENT source code.
- Removed lines cannot be highlighted in the current editor; returns their count.
"""

from dataclasses import dataclass, field
import hashlib
from typing import Optional


@dataclass(frozen=True)
class ChangedLine:
    """1-based line number in the current source version to highlight."""

    line: int
    module_id: Optional[str] = None


@dataclass(frozen=True)
class DiffResult:
    """Result of pure line-hash diff comparison."""

    changed_lines: list[ChangedLine] = field(default_factory=list)
    added_lines: list[int] = field(default_factory=list)
    modified_lines: list[int] = field(default_factory=list)
    removed_lines_count: int = 0
    comparison_available: bool = False
    comparison_note: Optional[str] = None


def compute_line_hashes(code: str) -> list[str]:
    """Compute SHA-256 hexadecimal digests for each line of source code.

    Invariants:
    - Splits lines deterministically using splitlines().
    - An empty string yields an empty list [].
    - UTF-8 encodes all text before hashing, handling Unicode lines safely.
    - Preserves line sequence and count.
    - Never retains or leaks the raw line text.
    """
    if not code:
        return []
    lines = code.splitlines()
    return [hashlib.sha256(line.encode("utf-8")).hexdigest() for line in lines]


def diff_line_hashes(
    previous_hashes: Optional[list[str]],
    current_code: str,
    module_id: Optional[str] = None,
) -> DiffResult:
    """Compare previous normal run line hashes against current source code.

    Uses a deterministic Longest Common Subsequence (LCS) edit script algorithm.
    Handles repeated identical lines, insertions, deletions, replacements,
    empty files, and trailing newlines deterministically.

    Args:
        previous_hashes: Ordered SHA-256 digests of the most recent normal run's lines,
                         or None if no earlier normal run exists.
        current_code: Source code of the current run.
        module_id: Optional Open-mode module identifier.

    Returns:
        DiffResult with 1-based current line numbers to highlight and metadata.
    """
    # Case B: No earlier normal run exists to compare against
    if previous_hashes is None:
        return DiffResult(
            changed_lines=[],
            added_lines=[],
            modified_lines=[],
            removed_lines_count=0,
            comparison_available=False,
            comparison_note="No earlier normal run to compare.",
        )

    current_hashes = compute_line_hashes(current_code)

    # Edge case: both versions are empty
    if not previous_hashes and not current_hashes:
        return DiffResult(
            changed_lines=[],
            added_lines=[],
            modified_lines=[],
            removed_lines_count=0,
            comparison_available=True,
            comparison_note=None,
        )

    # Edge case: previous was empty, current has content (all lines added)
    if not previous_hashes and current_hashes:
        all_added = list(range(1, len(current_hashes) + 1))
        changed = [ChangedLine(line=ln, module_id=module_id) for ln in all_added]
        return DiffResult(
            changed_lines=changed,
            added_lines=all_added,
            modified_lines=[],
            removed_lines_count=0,
            comparison_available=True,
            comparison_note=None,
        )

    # Edge case: previous had content, current is empty (all lines removed)
    if previous_hashes and not current_hashes:
        removed_count = len(previous_hashes)
        note = f"{removed_count} line{'s' if removed_count > 1 else ''} removed."
        return DiffResult(
            changed_lines=[],
            added_lines=[],
            modified_lines=[],
            removed_lines_count=removed_count,
            comparison_available=True,
            comparison_note=note,
        )

    # General case: Longest Common Subsequence (LCS) dynamic programming table
    m = len(previous_hashes)
    n = len(current_hashes)
    dp = [[0] * (n + 1) for _ in range(m + 1)]

    for i in range(m):
        for j in range(n):
            if previous_hashes[i] == current_hashes[j]:
                dp[i + 1][j + 1] = dp[i][j] + 1
            else:
                dp[i + 1][j + 1] = max(dp[i + 1][j], dp[i][j + 1])

    # Backtrack to reconstruct shortest edit script
    i, j = m, n
    edits: list[tuple[str, Optional[int], Optional[int]]] = []
    while i > 0 or j > 0:
        if i > 0 and j > 0 and previous_hashes[i - 1] == current_hashes[j - 1]:
            edits.append(("equal", i - 1, j - 1))
            i -= 1
            j -= 1
        elif j > 0 and (i == 0 or dp[i][j - 1] >= dp[i - 1][j]):
            edits.append(("insert", None, j - 1))
            j -= 1
        else:
            edits.append(("delete", i - 1, None))
            i -= 1

    edits.reverse()

    # Group contiguous blocks of delete and insert operations into modified, added, removed
    added_lines: list[int] = []
    modified_lines: list[int] = []
    removed_lines_count = 0

    idx = 0
    while idx < len(edits):
        tag = edits[idx][0]
        if tag == "equal":
            idx += 1
            continue

        del_count = 0
        ins_indices: list[int] = []

        while idx < len(edits) and edits[idx][0] in ("delete", "insert"):
            if edits[idx][0] == "delete":
                del_count += 1
            else:
                curr_idx = edits[idx][2]
                if curr_idx is not None:
                    ins_indices.append(curr_idx)
            idx += 1

        ins_count = len(ins_indices)
        # Pairs of (delete, insert) within the contiguous hunk represent modifications
        common_mod = min(del_count, ins_count)

        for k in range(common_mod):
            # 1-based line number in current source
            modified_lines.append(ins_indices[k] + 1)

        # Excess insertions represent additions in current source
        for k in range(common_mod, ins_count):
            added_lines.append(ins_indices[k] + 1)

        # Excess deletions represent removed lines
        if del_count > ins_count:
            removed_lines_count += del_count - ins_count

    # Combine added and modified current lines into changed_lines sorted by line number
    all_changed_line_numbers = sorted(set(added_lines + modified_lines))
    changed_lines = [
        ChangedLine(line=ln, module_id=module_id) for ln in all_changed_line_numbers
    ]

    note: Optional[str] = None
    if removed_lines_count > 0:
        note = f"{removed_lines_count} line{'s' if removed_lines_count > 1 else ''} removed."

    return DiffResult(
        changed_lines=changed_lines,
        added_lines=sorted(added_lines),
        modified_lines=sorted(modified_lines),
        removed_lines_count=removed_lines_count,
        comparison_available=True,
        comparison_note=note,
    )


def diff_modules_changed_lines(
    previous_module_hashes: Optional[dict[str, list[str]]],
    current_modules: dict[str, str],
) -> DiffResult:
    """Compare multiple modules for Open mode (FR37.3, US-69).

    Args:
        previous_module_hashes: Map of module_id -> ordered SHA-256 line hashes of last normal run,
                                or None if no earlier normal run exists.
        current_modules: Map of module_id -> current source code string.

    Returns:
        DiffResult aggregating changed lines across modules with module_id tagged.
    """
    if previous_module_hashes is None:
        return DiffResult(
            changed_lines=[],
            added_lines=[],
            modified_lines=[],
            removed_lines_count=0,
            comparison_available=False,
            comparison_note="No earlier normal run to compare.",
        )

    all_changed_lines: list[ChangedLine] = []
    total_added: list[int] = []
    total_modified: list[int] = []
    total_removed = 0

    for module_id, current_code in current_modules.items():
        prev_hashes = previous_module_hashes.get(module_id, [])
        res = diff_line_hashes(
            previous_hashes=prev_hashes,
            current_code=current_code,
            module_id=module_id,
        )
        all_changed_lines.extend(res.changed_lines)
        total_added.extend(res.added_lines)
        total_modified.extend(res.modified_lines)
        total_removed += res.removed_lines_count

    note: Optional[str] = None
    if total_removed > 0:
        note = f"{total_removed} line{'s' if total_removed > 1 else ''} removed across modules."

    return DiffResult(
        changed_lines=all_changed_lines,
        added_lines=total_added,
        modified_lines=total_modified,
        removed_lines_count=total_removed,
        comparison_available=True,
        comparison_note=note,
    )
