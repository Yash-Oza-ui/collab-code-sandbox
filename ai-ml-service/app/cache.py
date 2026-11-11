"""Normalized code cache and tokenizer-aware code normalizer (NFR5.2, NFR8.2, US-22, T-18 v2 Decision 11).

Implements:
1. Tokenizer-aware per-language code normalization across C++, Python, C, Java, and JavaScript (DR1):
   - Removes comments without touching comment markers inside string literals.
   - Normalizes non-semantic whitespace and collapses blank lines.
   - Preserves semantic Python indentation (INDENT/DEDENT levels).
2. SHA-256 cache key calculation over (normalized_code, language, request_type, error_output).
3. Thread-safe in-memory AnalysisCache storing ONLY 64-character SHA-256 digests as keys.
   Never stores or logs raw user code or error text (NFR8.2).
"""

import hashlib
import io
import logging
import re
import threading
import tokenize
from typing import Any, Optional

logger = logging.getLogger(__name__)

# Optional US-75 telemetry hook: record cache hits safely when counters module is present
try:
    from app.counters import record_cache_hit
except ImportError:
    def record_cache_hit(room_id: str) -> None:  # type: ignore[misc]
        pass


def _is_standalone_docstring(tokens_on_line: list[tuple[int, str]]) -> bool:
    """Return True if a logical statement line consists solely of a triple-quoted docstring/block comment."""
    if len(tokens_on_line) != 1:
        return False
    tok_type, tok_str = tokens_on_line[0]
    if tok_type != tokenize.STRING:
        return False
    stripped = tok_str.lstrip("rRuUfFbB")
    return stripped.startswith('"""') or stripped.startswith("'''")


def _normalize_python(code: str) -> str:
    """Normalize Python code using standard-library tokenize (preserves indentation and strings)."""
    if not code or not code.strip():
        return ""

    try:
        tokens = tokenize.tokenize(io.BytesIO(code.encode("utf-8")).readline)
        lines: list[str] = []
        curr_line: list[tuple[int, str]] = []
        indent_level = 0
        indent_str = ""

        for tok in tokens:
            tok_type = tok.type
            tok_str = tok.string

            if tok_type in (tokenize.COMMENT, tokenize.ENCODING):
                continue
            if tok_type == tokenize.NL:
                continue
            if tok_type == tokenize.NEWLINE:
                if curr_line:
                    if not _is_standalone_docstring(curr_line):
                        lines.append(indent_str + " ".join(s for _, s in curr_line))
                    curr_line = []
                continue
            if tok_type == tokenize.INDENT:
                indent_level += 1
                indent_str = "    " * indent_level
                continue
            if tok_type == tokenize.DEDENT:
                indent_level = max(0, indent_level - 1)
                indent_str = "    " * indent_level
                continue
            if tok_type == tokenize.ENDMARKER:
                if curr_line and not _is_standalone_docstring(curr_line):
                    lines.append(indent_str + " ".join(s for _, s in curr_line))
                break

            curr_line.append((tok_type, tok_str))

        return "\n".join(lines).strip()
    except Exception:
        # Fallback if code contains incomplete/unterminated literals or syntax errors:
        # still preserve line indentation and strip line comments outside quotes safely.
        clean_lines: list[str] = []
        for line in code.splitlines():
            indent = len(line) - len(line.lstrip())
            content = _strip_python_line_comment(line.strip())
            if content:
                clean_lines.append((" " * indent) + re.sub(r"\s+", " ", content))
        return "\n".join(clean_lines).strip()


def _strip_python_line_comment(line: str) -> str:
    """Strip a # comment from a single Python line while respecting string literals."""
    in_str: Optional[str] = None
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if in_str is not None:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue
        if c in ('"', "'"):
            in_str = c
            i += 1
            continue
        if c == "#":
            return line[:i].rstrip()
        i += 1
    return line.rstrip()


def _strip_comments_c_style(code: str, is_js: bool = False) -> str:
    """Character-scanner routine stripping // and /* */ comments while ignoring string literals."""
    i = 0
    n = len(code)
    out: list[str] = []

    in_str: Optional[str] = None
    in_line_comment = False
    in_block_comment = False

    while i < n:
        c = code[i]

        if in_line_comment:
            if c == "\n":
                in_line_comment = False
                out.append("\n")
            i += 1
            continue

        if in_block_comment:
            if c == "*" and i + 1 < n and code[i + 1] == "/":
                in_block_comment = False
                out.append(" ")
                i += 2
                continue
            i += 1
            continue

        if in_str is not None:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(code[i + 1])
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue

        # Detect start of line or block comment outside strings
        if c == "/" and i + 1 < n:
            next_c = code[i + 1]
            if next_c == "/":
                in_line_comment = True
                i += 2
                continue
            elif next_c == "*":
                in_block_comment = True
                i += 2
                continue

        # Detect start of string literal (double quotes, single quotes, JS template backticks)
        if c in ('"', "'") or (is_js and c == "`"):
            in_str = c
            out.append(c)
            i += 1
            continue

        out.append(c)
        i += 1

    return "".join(out)


def _normalize_c_style(code: str, is_js: bool = False) -> str:
    """Normalize C, C++, Java, or JavaScript code while preserving string literals verbatim."""
    if not code or not code.strip():
        return ""

    no_comments = _strip_comments_c_style(code, is_js=is_js)

    # Split text preserving string literals (double, single, backtick) untouched
    if is_js:
        split_pattern = r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`(?:\\.|[^`\\])*`)'
    else:
        split_pattern = r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')'

    parts = re.split(split_pattern, no_comments)
    normalized_parts: list[str] = []

    for idx, part in enumerate(parts):
        if idx % 2 == 1:
            # Literal token: keep exact string contents intact
            normalized_parts.append(part)
        else:
            sub = re.sub(r"\s+", " ", part)
            # Collapse whitespace around structural punctuation
            sub = re.sub(r"\s*([;,\(\)\{\}\[\]])\s*", r"\1", sub)
            # Standardize spacing around multi-char or single-char operators
            sub = re.sub(r"\s*([=+\-*/<>!&|]+)\s*", r" \1 ", sub)
            sub = re.sub(r"\s+", " ", sub).strip()
            if sub:
                normalized_parts.append(sub)

    return " ".join(normalized_parts).strip()


def normalize_code(code: str, language: str = "python") -> str:
    """Normalize source code across all five DR1 languages (NFR5.2, Decision 11).

    Supported languages:
    - python: Tokenized via Python `tokenize`, keeping semantic indentation and strings.
    - cpp / c / java: C-style comment scanner and punctuation/whitespace normalizer keeping strings intact.
    - javascript: C-style + backtick template literal-aware normalizer.
    """
    if not code:
        return ""

    lang = (language or "").lower()
    if lang == "python":
        return _normalize_python(code)
    if lang == "javascript":
        return _normalize_c_style(code, is_js=True)
    if lang in ("cpp", "c", "java"):
        return _normalize_c_style(code, is_js=False)

    # Safe default fallback for unknown languages
    return _normalize_c_style(code, is_js=False)


def compute_cache_key(
    code: str,
    language: str,
    request_type: str = "complexity",
    error_output: str = "",
) -> str:
    """Compute SHA-256 cache key over normalized code, language, request type, and error output (NFR5.2, Decision 11).

    Returns a 64-character lowercase hexadecimal digest. Raw code is never stored or logged.
    """
    normalized = normalize_code(code, language=language)
    norm_error = (error_output or "").strip()

    hasher = hashlib.sha256()
    hasher.update(normalized.encode("utf-8"))
    hasher.update(b"\n")
    hasher.update((language or "").lower().encode("utf-8"))
    hasher.update(b"\n")
    hasher.update((request_type or "").lower().encode("utf-8"))
    hasher.update(b"\n")
    hasher.update(norm_error.encode("utf-8"))
    return hasher.hexdigest()


class AnalysisCache:
    """Thread-safe in-memory cache holding SHA-256 hash keys only (NFR5.2, NFR8.2)."""

    def __init__(self, max_entries: int = 1000) -> None:
        self._lock = threading.Lock()
        self._max_entries = max_entries
        self._cache: dict[str, dict[str, Any]] = {}
        self._room_keys: dict[str, set[str]] = {}
        self._key_rooms: dict[str, set[str]] = {}

    def _track_room_unlocked(self, key: str, room_id: Optional[str]) -> None:
        if room_id:
            self._room_keys.setdefault(room_id, set()).add(key)
            self._key_rooms.setdefault(key, set()).add(room_id)

    def get(self, key: str, room_id: Optional[str] = None) -> Optional[dict[str, Any]]:
        """Retrieve cached AI result by SHA-256 hash key without logging any code."""
        with self._lock:
            if key in self._cache:
                self._track_room_unlocked(key, room_id)
                if room_id:
                    record_cache_hit(room_id)
                logger.debug("Cache hit for key=%s room_id=%s", key[:12], room_id)
                return dict(self._cache[key])
            return None

    def set(self, key: str, value: dict[str, Any], room_id: Optional[str] = None) -> None:
        """Store AI result under SHA-256 hash key (never stores raw code)."""
        with self._lock:
            if key not in self._cache and len(self._cache) >= self._max_entries:
                oldest_key = next(iter(self._cache))
                del self._cache[oldest_key]
                rooms = self._key_rooms.pop(oldest_key, set())
                for r in rooms:
                    r_keys = self._room_keys.get(r)
                    if r_keys is not None:
                        r_keys.discard(oldest_key)
                        if not r_keys:
                            del self._room_keys[r]

            self._cache[key] = dict(value)
            self._track_room_unlocked(key, room_id)
            logger.debug("Cache store for key=%s room_id=%s", key[:12], room_id)

    def clear_room(self, room_id: str) -> None:
        """Delete all cached entries scoped to a specific room (US-56, FR32)."""
        with self._lock:
            keys = self._room_keys.pop(room_id, None)
            if keys:
                for k in keys:
                    rooms = self._key_rooms.get(k)
                    if rooms is not None:
                        rooms.discard(room_id)
                        if not rooms:
                            del self._key_rooms[k]
                            self._cache.pop(k, None)
                    else:
                        self._cache.pop(k, None)

    def clear(self) -> None:
        """Clear all cache entries."""
        with self._lock:
            self._cache.clear()
            self._room_keys.clear()
            self._key_rooms.clear()

    def get_all_keys(self) -> list[str]:
        """Return all stored keys for invariant testing (verifying hashes-only storage)."""
        with self._lock:
            return list(self._cache.keys())


# Default global cache instance
_default_cache = AnalysisCache()


def get_analysis_cache() -> AnalysisCache:
    """Retrieve the global default analysis cache."""
    return _default_cache


def clear_analysis_cache() -> None:
    """Clear all cached entries."""
    _default_cache.clear()
