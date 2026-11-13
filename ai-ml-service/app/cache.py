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


def _normalize_python(code: str) -> str:
    """Normalize Python code using standard-library tokenize (preserves indentation, strings, and docstrings)."""
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
                if curr_line:
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
    """Character-scanner routine stripping // and /* */ comments while ignoring string literals.

    In JavaScript, a multi-line comment containing at least one line break acts as a LineTerminator
    for Automatic Semicolon Insertion (ECMAScript §12.9.1).
    """
    i = 0
    n = len(code)
    out: list[str] = []

    in_str: Optional[str] = None
    in_line_comment = False
    in_block_comment = False
    block_had_newline = False

    while i < n:
        c = code[i]

        if in_line_comment:
            if c == "\n":
                in_line_comment = False
                out.append("\n")
            i += 1
            continue

        if in_block_comment:
            if c == "\n":
                block_had_newline = True
            if c == "*" and i + 1 < n and code[i + 1] == "/":
                in_block_comment = False
                out.append("\n" if (is_js and block_had_newline) else " ")
                block_had_newline = False
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
                block_had_newline = False
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


def _normalize_c_style(code: str) -> str:
    """Normalize C, C++, or Java code while preserving string literals verbatim."""
    if not code or not code.strip():
        return ""

    no_comments = _strip_comments_c_style(code, is_js=False)
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


# ECMAScript restricted productions and ASI boundaries (ECMAScript §12.9, §13.1, NFR5.2, US-22)
_JS_RESTRICTED_KEYWORDS = {"return", "throw", "break", "continue", "yield"}

_JS_NO_ASI_AFTER = {
    ";", "{", "(", "[", ",", ":", "?", ".", "=>",
    "=", "+=", "-=", "*=", "/=", "%=", "**=",
    "==", "===", "!=", "!==", "<", ">", "<=", ">=",
    "&&", "||", "??",
    "+", "-", "*", "/", "%", "**",
    "&", "|", "^", "~", "!", "typeof", "void", "delete", "in", "instanceof", "new",
}

_JS_NO_ASI_BEFORE = {
    ";", "}", ")", "]", ",", ":", "?", ".", "=>",
    "=", "+=", "-=", "*=", "/=", "%=", "**=",
    "==", "===", "!=", "!==", "<", ">", "<=", ">=",
    "&&", "||", "??",
    "*", "/", "%", "**",
    "&", "|", "^", "~", "in", "instanceof",
}

_JS_KEYWORD_NEEDS_SPACE = {
    "var", "let", "const", "function", "return", "throw", "case", "new",
    "typeof", "delete", "void", "in", "instanceof", "yield", "await",
}

_JS_TOKEN_RE = re.compile(
    r'(\n(?:[ \t]*\n)*)'                        # 1: newline(s)
    r'|([a-zA-Z_$][a-zA-Z0-9_$]*)'               # 2: word / identifier / keyword
    r'|(\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)'         # 3: number
    r'|(===|!==|==|!=|<=|>=|&&|\|\||\?\?|=>|\+\+|--|\+=|-=|\*=|/=|%=|\*\*=|<<=|>>=|>>>=|\.\.\.)' # 4: multi-char ops
    r'|([;,\(\)\{\}\[\]:\?\.\+\-\*\/%&\|\^~!=<>])' # 5: single-char punct
    r'|([ \t]+)'                                 # 6: horizontal whitespace
    r'|(\S)'                                     # 7: fallback for any other char
)


def _normalize_javascript(code: str) -> str:
    """Normalize JavaScript code while strictly preserving semantic line breaks (ASI, NFR5.2, US-22)."""
    if not code or not code.strip():
        return ""

    no_comments = _strip_comments_c_style(code, is_js=True)
    split_pattern = r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`(?:\\.|[^`\\])*`)'
    parts = re.split(split_pattern, no_comments)

    unified_items: list[tuple[bool, str]] = []
    for idx, part in enumerate(parts):
        if idx % 2 == 1:
            # String or template literal: keep verbatim
            unified_items.append((True, part))
        else:
            if not part:
                continue
            for m in _JS_TOKEN_RE.finditer(part):
                nl, word, num, multi_op, punct, hws, other = m.groups()
                if nl:
                    unified_items.append((False, "\n"))
                elif word:
                    unified_items.append((False, word))
                elif num:
                    unified_items.append((False, num))
                elif multi_op:
                    unified_items.append((False, multi_op))
                elif punct:
                    unified_items.append((False, punct))
                elif other:
                    unified_items.append((False, other))

    out_tokens: list[str] = []
    n = len(unified_items)

    for i, (is_lit, tok) in enumerate(unified_items):
        if is_lit:
            out_tokens.append(tok)
            continue

        if tok != "\n":
            out_tokens.append(tok)
            continue

        prev_tok = None
        for j in range(i - 1, -1, -1):
            if unified_items[j][1] != "\n":
                prev_tok = unified_items[j][1]
                break

        next_tok = None
        for j in range(i + 1, n):
            if unified_items[j][1] != "\n":
                next_tok = unified_items[j][1]
                break

        if prev_tok is None or next_tok is None:
            continue

        is_semantic = False
        if prev_tok in _JS_RESTRICTED_KEYWORDS:
            is_semantic = True
        elif next_tok in ("++", "--"):
            is_semantic = True
        elif prev_tok not in _JS_NO_ASI_AFTER and next_tok not in _JS_NO_ASI_BEFORE:
            is_semantic = True

        if is_semantic:
            if not out_tokens or out_tokens[-1] != "\n":
                out_tokens.append("\n")

    res: list[str] = []
    for i, t in enumerate(out_tokens):
        if t == "\n":
            res.append("\n")
            continue
        if i > 0:
            prev = out_tokens[i - 1]
            if prev != "\n":
                prev_is_word = bool(re.match(r"^[a-zA-Z0-9_$]+$", prev))
                t_is_word = bool(re.match(r"^[a-zA-Z0-9_$]+$", t))
                if prev_is_word and t_is_word:
                    res.append(" ")
                elif prev in _JS_KEYWORD_NEEDS_SPACE and t not in (";", ",", ")", "]", "}"):
                    res.append(" ")
                elif t in ("in", "instanceof"):
                    res.append(" ")
        res.append(t)

    return "".join(res).strip()


def normalize_code(code: str, language: str = "python") -> str:
    """Normalize source code across all five DR1 languages (NFR5.2, Decision 11).

    Supported languages:
    - python: Tokenized via Python `tokenize`, keeping semantic indentation, strings, and docstrings.
    - cpp / c / java: C-style comment scanner and punctuation/whitespace normalizer keeping strings intact.
    - javascript: Semantic line-break and ASI-aware normalizer preserving template literals and statement boundaries.
    """
    if not code:
        return ""

    lang = (language or "").lower()
    if lang == "python":
        return _normalize_python(code)
    if lang == "javascript":
        return _normalize_javascript(code)
    if lang in ("cpp", "c", "java"):
        return _normalize_c_style(code)

    # Safe default fallback for unknown languages
    return _normalize_c_style(code)


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
