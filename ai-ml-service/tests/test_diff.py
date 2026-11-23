"""Unit tests for pure line-hash diffing function (US-69, FR37.3, Sprint 5).

Verifies:
- Deterministic Longest Common Subsequence (LCS) edit script algorithm.
- One-based line numbering referring to the current source code.
- Added, modified, and removed line count calculations.
- Handling of repeated identical lines and duplicate lines with insertions.
- Handling of empty files, trailing newlines, and Unicode source lines.
- Open mode multiple module diffing with module_id tagging.
- Privacy invariant: no source code strings or line text in DiffResult.
- Availability status when previous_hashes is None (no earlier normal run).
"""

import hashlib
import pytest

from app.diff import (
    ChangedLine,
    DiffResult,
    compute_line_hashes,
    diff_line_hashes,
    diff_modules_changed_lines,
)


def test_compute_line_hashes_basic():
    """Verify compute_line_hashes returns ordered SHA-256 digests and no raw code."""
    code = "a = 10\nb = 20\nprint(a + b)"
    hashes = compute_line_hashes(code)
    assert len(hashes) == 3
    for h in hashes:
        assert isinstance(h, str)
        assert len(h) == 64
        # Verify it is valid hex
        int(h, 16)

    # First line hash matches manual SHA-256 of 'a = 10'
    expected_first = hashlib.sha256(b"a = 10").hexdigest()
    assert hashes[0] == expected_first


def test_compute_line_hashes_empty():
    """Verify empty string produces empty list."""
    assert compute_line_hashes("") == []
    assert compute_line_hashes(None) == []


def test_diff_no_changes():
    """Verify identical files produce zero changed lines and zero removed lines."""
    code = "a = 10\nb = 20\nprint(a + b)"
    prev_hashes = compute_line_hashes(code)
    res = diff_line_hashes(prev_hashes, code)

    assert res.comparison_available is True
    assert res.changed_lines == []
    assert res.added_lines == []
    assert res.modified_lines == []
    assert res.removed_lines_count == 0
    assert res.comparison_note is None


def test_diff_one_modified_line():
    """Verify single modified line produces correct 1-based line number."""
    # Example from US-69 specification:
    # Previous: a = 10\nb = 20\nprint(a + b)
    # Current:  a = 10\nb = 50\nprint(a + b)
    prev = "a = 10\nb = 20\nprint(a + b)"
    curr = "a = 10\nb = 50\nprint(a + b)"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.modified_lines == [2]
    assert res.added_lines == []
    assert res.removed_lines_count == 0
    assert len(res.changed_lines) == 1
    assert res.changed_lines[0].line == 2
    assert res.changed_lines[0].module_id is None


def test_diff_one_added_line():
    """Verify addition of a line produces correct 1-based current line number."""
    # Example from US-69 specification:
    # Previous: a = 10\nb = 20\nprint(a + b)
    # Current:  a = 10\nb = 50\nprint(a + b)\nprint("done")
    prev = "a = 10\nb = 20\nprint(a + b)"
    curr = "a = 10\nb = 50\nprint(a + b)\nprint(\"done\")"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.modified_lines == [2]
    assert res.added_lines == [4]
    assert res.removed_lines_count == 0
    assert [c.line for c in res.changed_lines] == [2, 4]


def test_diff_one_removed_line():
    """Verify removed lines are counted but not highlighted in current code."""
    # Example from US-69 specification:
    # Previous: a = 10\nb = 20\nc = 30\nprint(a + b + c)
    # Current:  a = 10\nc = 30\nprint(a + c)
    prev = "a = 10\nb = 20\nc = 30\nprint(a + b + c)"
    curr = "a = 10\nc = 30\nprint(a + c)"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.removed_lines_count == 1
    assert res.modified_lines == [3]
    assert res.added_lines == []
    assert [c.line for c in res.changed_lines] == [3]
    assert "1 line removed" in (res.comparison_note or "")


def test_diff_multiple_additions_and_modifications():
    """Verify multiple contiguous and non-contiguous changes are identified."""
    prev = "line1\nline2\nline3\nline4\nline5"
    curr = "line1\nline2_mod\nline3\nline4_mod\nline5\nline6_add\nline7_add"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.modified_lines == [2, 4]
    assert res.added_lines == [6, 7]
    assert res.removed_lines_count == 0
    assert [c.line for c in res.changed_lines] == [2, 4, 6, 7]


def test_diff_repeated_identical_lines():
    """Verify algorithm handles repeated identical lines deterministically without naive set issues."""
    prev = "item\nitem\nitem"
    curr = "item\nnew_item\nitem\nitem"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.added_lines == [2]
    assert res.modified_lines == []
    assert res.removed_lines_count == 0
    assert [c.line for c in res.changed_lines] == [2]


def test_diff_duplicate_lines_with_insertion_before_them():
    """Verify duplicate lines after an insertion are correctly offset."""
    prev = "print(x)\nprint(x)"
    curr = "x = 1\nprint(x)\nprint(x)"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.added_lines == [1]
    assert res.modified_lines == []
    assert res.removed_lines_count == 0
    assert [c.line for c in res.changed_lines] == [1]


def test_diff_empty_previous_file():
    """Verify all current lines are treated as added when previous file was empty."""
    prev = ""
    curr = "x = 10\ny = 20"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.added_lines == [1, 2]
    assert res.modified_lines == []
    assert res.removed_lines_count == 0
    assert [c.line for c in res.changed_lines] == [1, 2]


def test_diff_empty_current_file():
    """Verify empty current file returns zero highlights and counts all removed lines."""
    prev = "x = 10\ny = 20\nz = 30"
    curr = ""
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.changed_lines == []
    assert res.added_lines == []
    assert res.modified_lines == []
    assert res.removed_lines_count == 3
    assert "3 lines removed" in (res.comparison_note or "")


def test_diff_trailing_newline_differences():
    """Verify splitlines() normalizes trailing newlines so no false changes occur."""
    prev = "a = 1\nb = 2\n"
    curr = "a = 1\nb = 2"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.changed_lines == []
    assert res.removed_lines_count == 0


def test_diff_unicode_source_lines():
    """Verify Unicode code characters are hashed and compared safely."""
    prev = 'message = "Hello, world!"\ncount = 1'
    curr = 'message = "Hello, \U0001f30d!"\ncount = 1'
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr)

    assert res.comparison_available is True
    assert res.modified_lines == [1]
    assert res.added_lines == []
    assert res.removed_lines_count == 0
    assert [c.line for c in res.changed_lines] == [1]


def test_diff_no_earlier_normal_run():
    """Verify previous_hashes=None returns comparison_available=False and explanatory note."""
    curr = "x = 10\ny = 20"
    res = diff_line_hashes(None, curr)

    assert res.comparison_available is False
    assert res.changed_lines == []
    assert res.added_lines == []
    assert res.modified_lines == []
    assert res.removed_lines_count == 0
    assert res.comparison_note == "No earlier normal run to compare."


def test_diff_with_module_id():
    """Verify module_id is properly tagged on ChangedLine items."""
    prev = "def foo():\n    return 1"
    curr = "def foo():\n    return 2"
    prev_hashes = compute_line_hashes(prev)
    res = diff_line_hashes(prev_hashes, curr, module_id="mod_9Qx7")

    assert res.comparison_available is True
    assert len(res.changed_lines) == 1
    assert res.changed_lines[0].line == 2
    assert res.changed_lines[0].module_id == "mod_9Qx7"


def test_diff_modules_open_mode():
    """Verify multi-module Open mode comparison separates highlights by module_id."""
    prev_modules = {
        "mod_main": compute_line_hashes("import helper\nprint(helper.calc(5))"),
        "mod_helper": compute_line_hashes("def calc(x):\n    return x * 2"),
    }
    curr_modules = {
        "mod_main": "import helper\nprint(helper.calc(5))\nprint('done')",
        "mod_helper": "def calc(x):\n    return x * 10",
    }
    res = diff_modules_changed_lines(prev_modules, curr_modules)

    assert res.comparison_available is True
    # mod_main added line 3
    main_changes = [c for c in res.changed_lines if c.module_id == "mod_main"]
    assert len(main_changes) == 1
    assert main_changes[0].line == 3

    # mod_helper modified line 2
    helper_changes = [c for c in res.changed_lines if c.module_id == "mod_helper"]
    assert len(helper_changes) == 1
    assert helper_changes[0].line == 2


def test_diff_modules_no_earlier_run():
    """Verify multi-module comparison returns no highlights when previous_module_hashes is None."""
    curr_modules = {"mod_main": "print(1)"}
    res = diff_modules_changed_lines(None, curr_modules)
    assert res.comparison_available is False
    assert res.changed_lines == []
    assert res.comparison_note == "No earlier normal run to compare."


def test_diff_deterministic_output():
    """Verify executing diff multiple times yields identical results."""
    prev = "a = 1\nb = 2\nc = 3"
    curr = "a = 1\nb = 99\nc = 3\nd = 4"
    prev_hashes = compute_line_hashes(prev)

    res1 = diff_line_hashes(prev_hashes, curr)
    res2 = diff_line_hashes(prev_hashes, curr)

    assert res1.changed_lines == res2.changed_lines
    assert res1.added_lines == res2.added_lines
    assert res1.modified_lines == res2.modified_lines
    assert res1.removed_lines_count == res2.removed_lines_count
    assert res1.comparison_note == res2.comparison_note


def test_privacy_invariant_no_raw_source_in_result():
    """Verify canary marker is absent from all fields of DiffResult and compute_line_hashes."""
    canary_marker = "US69_CANARY_MARKER_MUST_NEVER_APPEAR"
    curr = f"def run():\n    return '{canary_marker}'"
    prev_hashes = compute_line_hashes("def run():\n    return 'initial'")

    res = diff_line_hashes(prev_hashes, curr)
    assert res.comparison_available is True

    # Serialize result representation
    result_str = str(res)
    assert canary_marker not in result_str, "Canary marker leaked into DiffResult string representation!"

    # Also verify compute_line_hashes output
    hashes = compute_line_hashes(curr)
    for h in hashes:
        assert canary_marker not in h
