"""Normalized code cache and tokenizer-aware code normalizer (NFR5.2, NFR8.2, US-22, US-56, FR40.1, T-18 v2 Decision 11).

Implements:
1. Tokenizer-aware per-language code normalization across C++, Python, C, Java, and JavaScript (DR1):
   - Removes comments without touching comment markers inside string literals.
   - Normalizes non-semantic whitespace and collapses blank lines.
   - Preserves semantic Python indentation (INDENT/DEDENT levels) and docstrings.
   - Preserves JavaScript ASI line breaks and statement boundaries.
2. SHA-256 cache key calculation over (normalized_code, language, request_type, error_signature).
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

from app.counters import record_cache_hit

logger = logging.getLogger(__name__)


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


def _scan_c_style_lexemes(
    code: str,
    is_js: bool = False,
    language: str = "cpp",
) -> list[tuple[bool, str]]:
    """Scan source code character-by-character, stripping comments and identifying literals verbatim.

    Returns a list of tuples: (is_literal: bool, content: str).
    Preserves:
    - C++ raw string literals R"delim(...)delim" (and prefixes u8R, uR, UR, LR)
    - Java text blocks \"\"\"...\"\"\"
    - JavaScript regex literals /pattern/flags (in expression context)
    - Standard single- and double-quoted strings and JS template literals
    - Line breaks between preprocessor directives and subsequent code
    - Line breaks inside JS block comments (for ASI)
    """
    i = 0
    n = len(code)
    lexemes: list[tuple[bool, str]] = []
    code_buf: list[str] = []
    last_non_ws_tok = ""
    paren_stack: list[Optional[str]] = []
    last_control_keyword: Optional[str] = None
    last_closed_paren_was_control: bool = False

    def flush_code() -> None:
        if code_buf:
            lexemes.append((False, "".join(code_buf)))
            code_buf.clear()

    while i < n:
        c = code[i]

        # 1. Java text block: """..."""
        if c == '"' and i + 2 < n and code[i:i + 3] == '"""':
            flush_code()
            lit = ['"""']
            i += 3
            while i < n:
                if code[i] == "\\" and i + 1 < n:
                    lit.append(code[i:i + 2])
                    i += 2
                    continue
                if code[i] == '"' and i + 2 < n and code[i:i + 3] == '"""':
                    lit.append('"""')
                    i += 3
                    break
                lit.append(code[i])
                i += 1
            lexemes.append((True, "".join(lit)))
            last_non_ws_tok = "literal"
            continue

        # 2. C/C++ string/char literals with optional encoding prefixes (u8, u, U, L) and raw strings (R)
        if not is_js:
            prefix_match = None
            if code[i] == "R" and i + 1 < n and code[i + 1] == '"':
                prefix_match = ("raw", 1, i + 1)
            elif i + 2 < n and code[i:i + 2] in ("LR", "uR", "UR") and code[i + 2] == '"':
                prefix_match = ("raw", 2, i + 2)
            elif i + 3 < n and code[i:i + 3] == "u8R" and code[i + 3] == '"':
                prefix_match = ("raw", 3, i + 3)
            elif i + 2 < n and code[i:i + 2] == "u8" and code[i + 2] == '"':
                prefix_match = ("str", 2, i + 2)
            elif code[i] in ("u", "U", "L") and i + 1 < n and code[i + 1] == '"':
                prefix_match = ("str", 1, i + 1)
            elif i + 2 < n and code[i:i + 2] == "u8" and code[i + 2] == "'":
                prefix_match = ("char", 2, i + 2)
            elif code[i] in ("u", "U", "L") and i + 1 < n and code[i + 1] == "'":
                prefix_match = ("char", 1, i + 1)

            if prefix_match and (i == 0 or not (code[i - 1].isalnum() or code[i - 1] == "_")):
                kind, plen, qidx = prefix_match
                if kind == "raw":
                    line_end = code.find("\n", qidx + 1)
                    if line_end == -1:
                        line_end = n
                    paren_idx = code.find("(", qidx + 1, min(line_end, qidx + 18))
                    if paren_idx != -1:
                        delim = code[qidx + 1:paren_idx]
                        if len(delim) <= 16 and all(ch not in " ()\\\t\r\n" for ch in delim):
                            flush_code()
                            target_close = f"){delim}\""
                            raw_buf = [code[i:paren_idx + 1]]
                            i = paren_idx + 1
                            while i < n:
                                if code[i:i + len(target_close)] == target_close:
                                    raw_buf.append(target_close)
                                    i += len(target_close)
                                    break
                                raw_buf.append(code[i])
                                i += 1
                            lexemes.append((True, "".join(raw_buf)))
                            last_non_ws_tok = "literal"
                            continue
                elif kind in ("str", "char"):
                    flush_code()
                    delim = code[qidx]
                    lit_buf = [code[i:qidx + 1]]
                    i = qidx + 1
                    while i < n:
                        ch = code[i]
                        if ch == "\\" and i + 1 < n:
                            lit_buf.append(code[i:i + 2])
                            i += 2
                            continue
                        lit_buf.append(ch)
                        i += 1
                        if ch == delim:
                            break
                    lexemes.append((True, "".join(lit_buf)))
                    last_non_ws_tok = "literal"
                    continue

        # 3. Standard string literals: "..." and '...'
        if c in ('"', "'"):
            flush_code()
            delim = c
            lit = [delim]
            i += 1
            while i < n:
                ch = code[i]
                if ch == "\\" and i + 1 < n:
                    lit.append(code[i:i + 2])
                    i += 2
                    continue
                lit.append(ch)
                i += 1
                if ch == delim:
                    break
            lexemes.append((True, "".join(lit)))
            last_non_ws_tok = "literal"
            continue

        # 4. JavaScript template literal: `...`
        if is_js and c == "`":
            flush_code()
            lit = ["`"]
            i += 1
            while i < n:
                ch = code[i]
                if ch == "\\" and i + 1 < n:
                    lit.append(code[i:i + 2])
                    i += 2
                    continue
                lit.append(ch)
                i += 1
                if ch == "`":
                    break
            lexemes.append((True, "".join(lit)))
            last_non_ws_tok = "literal"
            continue

        # 5. JavaScript regex literal: /pattern/flags
        if is_js and c == "/" and i + 1 < n and code[i + 1] not in ("/", "*"):
            is_regex_start = False
            if last_non_ws_tok == ")" and last_closed_paren_was_control:
                is_regex_start = True
            elif last_non_ws_tok in (
                "", "(", "[", "{", ";", ",", "=", ":", "?", "!", "&", "|",
                "+", "-", "*", "%", "^", "~", "<", ">",
                "return", "throw", "yield", "await", "case", "delete", "void",
                "typeof", "instanceof", "in", "do", "else"
            ):
                is_regex_start = True

            if is_regex_start:
                flush_code()
                reg = ["/"]
                i += 1
                in_cc = False
                while i < n:
                    ch = code[i]
                    if ch == "\n":
                        break
                    if ch == "\\" and i + 1 < n:
                        reg.append(code[i:i + 2])
                        i += 2
                        continue
                    if ch == "[":
                        in_cc = True
                    elif ch == "]" and in_cc:
                        in_cc = False
                    elif ch == "/" and not in_cc:
                        reg.append("/")
                        i += 1
                        while i < n and code[i].isalpha():
                            reg.append(code[i])
                            i += 1
                        break
                    reg.append(ch)
                    i += 1
                lexemes.append((True, "".join(reg)))
                last_non_ws_tok = "literal"
                last_closed_paren_was_control = False
                continue

        # 6. Comments
        if c == "/" and i + 1 < n and code[i + 1] == "/":
            i += 2
            while i < n and code[i] != "\n":
                i += 1
            if i < n and code[i] == "\n":
                code_buf.append("\n")
                i += 1
            continue

        if c == "/" and i + 1 < n and code[i + 1] == "*":
            i += 2
            had_nl = False
            while i < n:
                if code[i] == "\n":
                    had_nl = True
                if code[i] == "*" and i + 1 < n and code[i + 1] == "/":
                    i += 2
                    break
                i += 1
            if is_js and had_nl:
                code_buf.append("\n")
            else:
                code_buf.append(" ")
            continue

        if is_js:
            if c == "(":
                paren_stack.append(last_control_keyword)
                last_control_keyword = None
            elif c == ")":
                if paren_stack:
                    k = paren_stack.pop()
                    last_closed_paren_was_control = (k in ("if", "while", "for", "with"))
                else:
                    last_closed_paren_was_control = False

        code_buf.append(c)
        if not c.isspace():
            if c.isalnum() or c in ("_", "$"):
                if last_non_ws_tok and (last_non_ws_tok[-1].isalnum() or last_non_ws_tok[-1] in ("_", "$")):
                    last_non_ws_tok += c
                else:
                    last_non_ws_tok = c
                if is_js and last_non_ws_tok in ("if", "while", "for", "with"):
                    last_control_keyword = last_non_ws_tok
            else:
                last_non_ws_tok = c
                if c != ")":
                    last_closed_paren_was_control = False
        i += 1

    flush_code()
    return lexemes


def _strip_comments_c_style(code: str, is_js: bool = False, language: str = "cpp") -> str:
    """Character-scanner routine stripping // and /* */ comments while preserving all language literals.

    In JavaScript, a multi-line comment containing at least one line break acts as a LineTerminator
    for Automatic Semicolon Insertion (ECMAScript §12.9.1).
    """
    lexemes = _scan_c_style_lexemes(code, is_js=is_js, language=language)
    return "".join(content for _, content in lexemes)


_C_OP_MULTI = {
    "::", "->*", "->", "++", "--", "<<=", ">>=", "<<", ">>", "<=", ">=",
    "==", "!=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "..."
}
_C_OP_SINGLE = {
    "+", "-", "*", "/", "%", "^", "&", "|", "~", "!", "=", "<", ">", "?", ":"
}
_C_STRUCTURAL_PUNCT = {
    ";", ",", "(", ")", "[", "]", "{", "}"
}

_C_TOKEN_RE = re.compile(
    r'(::|->\*|->|\+\+|--|<<=|>>=|<<|>>|<=|>=|==|!=|&&|\|\||\+=|-=|\*=|/=|%=|&=|\|=|\^=|\.\.\.)'  # 1: multi-char ops
    r'|([a-zA-Z_$][a-zA-Z0-9_$]*)'                                                                  # 2: word / identifier
    r'|(\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[a-zA-Z]*)'                                                   # 3: number
    r'|([;,\(\)\{\}\[\]])'                                                                           # 4: structural punct
    r'|([=+\-*/<>!&|~^%?:.])'                                                                        # 5: single-char ops
    r'|(\n)'                                                                                         # 6: newline
    r'|([ \t]+)'                                                                                     # 7: horizontal whitespace
    r'|(\\)'                                                                                         # 8: backslash
    r'|(\S)'                                                                                         # 9: other
)


def _normalize_c_tokens(tokens: list[tuple[bool, str]]) -> str:
    """Normalize a stream of (is_literal, token) into a whitespace-normalized string.

    Preserves operator token boundaries and standardizes spacing without merging separate operators.
    """
    out: list[str] = []

    for i, (is_lit, tok) in enumerate(tokens):
        if is_lit:
            if out and (out[-1][-1].isalnum() or out[-1][-1] in ("_", "$")):
                out.append(" ")
            elif out and (out[-1] in _C_OP_MULTI or out[-1] in _C_OP_SINGLE):
                out.append(" ")
            out.append(tok)
            continue

        if not out:
            out.append(tok)
            continue

        prev = out[-1]

        prev_is_word = bool(re.match(r"^[#a-zA-Z0-9_$]+$", prev))
        tok_is_word = bool(re.match(r"^[#a-zA-Z0-9_$]+$", tok))

        prev_is_op = (prev in _C_OP_MULTI or prev in _C_OP_SINGLE)
        tok_is_op = (tok in _C_OP_MULTI or tok in _C_OP_SINGLE)

        prev_is_punct = (prev in _C_STRUCTURAL_PUNCT)
        tok_is_punct = (tok in _C_STRUCTURAL_PUNCT)

        prev_is_lit = bool(prev.startswith(('"', "'", "`", "R\"", "u8", "u\"", "U\"", "L\"", "u'", "U'", "L'")) or prev.endswith(('"', "'", "`")))

        if prev_is_word and tok_is_word:
            out.append(" ")
        elif (prev_is_word or prev_is_lit) and tok_is_op:
            out.append(" ")
        elif prev_is_op and tok_is_word:
            out.append(" ")
        elif prev_is_op and tok_is_op:
            out.append(" ")
        elif prev_is_op and tok_is_punct:
            pass
        elif prev_is_punct and tok_is_op:
            pass

        out.append(tok)

    return "".join(out).strip()


def _normalize_c_style(code: str, language: str = "cpp") -> str:
    """Normalize C, C++, or Java code while preserving string literals, directives, and operator boundaries."""
    if not code or not code.strip():
        return ""

    lexemes = _scan_c_style_lexemes(code, is_js=False, language=language)

    # 1. Merge preprocessor continuation lines (\\ followed by newline) in non-literal content
    processed_lexemes: list[tuple[bool, str]] = []
    for is_lit, content in lexemes:
        if is_lit:
            processed_lexemes.append((True, content))
        else:
            no_cont = re.sub(r"\\(?:[ \t]*\r?\n)", " ", content)
            processed_lexemes.append((False, no_cont))

    # 2. Split into lines based on unescaped \n in non-literal tokens
    lines: list[list[tuple[bool, str]]] = [[]]

    for is_lit, content in processed_lexemes:
        if is_lit:
            lines[-1].append((True, content))
        else:
            for m in _C_TOKEN_RE.finditer(content):
                multi_op, word, num, punct, single_op, nl, hws, bslash, other = m.groups()
                if nl:
                    lines.append([])
                elif multi_op:
                    lines[-1].append((False, multi_op))
                elif word:
                    lines[-1].append((False, word))
                elif num:
                    lines[-1].append((False, num))
                elif punct:
                    lines[-1].append((False, punct))
                elif single_op:
                    lines[-1].append((False, single_op))
                elif other:
                    lines[-1].append((False, other))

    clean_lines = [line for line in lines if any(tok[1].strip() for tok in line)]

    # 3. Separate preprocessor lines (#...) from ordinary code lines
    result_segments: list[str] = []
    curr_code_tokens: list[tuple[bool, str]] = []

    def flush_curr_code() -> None:
        if curr_code_tokens:
            norm = _normalize_c_tokens(curr_code_tokens)
            if norm:
                result_segments.append(norm)
            curr_code_tokens.clear()

    for line in clean_lines:
        first_tok = next((tok[1] for tok in line if tok[1].strip()), None)
        if first_tok == "#":
            flush_curr_code()
            prep_tokens: list[tuple[bool, str]] = []
            saw_hash = False
            for is_lit, tok in line:
                if not saw_hash and tok == "#":
                    saw_hash = True
                    prep_tokens.append((False, "#"))
                elif saw_hash and prep_tokens and prep_tokens[-1][1] == "#":
                    prep_tokens[-1] = (False, "#" + tok)
                else:
                    prep_tokens.append((is_lit, tok))
            norm_prep = _normalize_c_tokens(prep_tokens)
            result_segments.append(norm_prep)
        else:
            curr_code_tokens.extend(line)

    flush_curr_code()
    return "\n".join(result_segments).strip()


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

    lexemes = _scan_c_style_lexemes(code, is_js=True, language="javascript")

    unified_items: list[tuple[bool, str]] = []
    for is_lit, part in lexemes:
        if is_lit:
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
        return _normalize_c_style(code, language=lang)

    return _normalize_c_style(code, language=lang)


def compute_cache_key(
    code: str,
    language: str,
    request_type: str = "complexity",
    error_output: str = "",
    error_signature: str = "",
) -> str:
    """Compute SHA-256 cache key over normalized code, language, request type, error signature, and error output (NFR5.2, Decision 11).

    Returns a 64-character lowercase hexadecimal digest. Raw code is never stored or logged (NFR8.2).
    """
    normalized = normalize_code(code, language=language)

    hasher = hashlib.sha256()
    hasher.update(normalized.encode("utf-8"))
    hasher.update(b":")
    hasher.update((language or "").lower().encode("utf-8"))
    hasher.update(b":")
    hasher.update((request_type or "").lower().encode("utf-8"))
    hasher.update(b":")
    hasher.update((error_signature or "").strip().encode("utf-8"))
    hasher.update(b":")
    hasher.update((error_output or "").strip().encode("utf-8"))
    return hasher.hexdigest()


class AnalysisCache:
    """Thread-safe in-memory cache for analysis results and AI suggestions (NFR5.2, NFR8.2, US-22, US-56, FR40.1)."""

    def __init__(self, max_entries: int = 1000) -> None:
        self._lock = threading.Lock()
        self._max_entries = max_entries
        # Room-scoped cache entries keyed by (room_id or "", sha256_key) so clearing
        # one room never deletes identical-hash entries belonging to another room.
        self._cache: dict[tuple[str, str], dict[str, Any]] = {}
        # Mapping of room_id to set of storage keys created within that room
        self._room_keys: dict[str, set[tuple[str, str]]] = {}

    @staticmethod
    def _storage_key(key: str, room_id: Optional[str]) -> tuple[str, str]:
        return (str(room_id) if room_id else "", key)

    def get(self, key: str, room_id: Optional[str] = None) -> Optional[dict[str, Any]]:
        """Retrieve cached result by room-scoped SHA-256 key and record a cache hit (FR40.1)."""
        storage_key = self._storage_key(key, room_id)
        with self._lock:
            if storage_key in self._cache:
                record_cache_hit(room_id)
                logger.debug("Cache hit for key=%s room_id=%s", key[:12], room_id)
                return dict(self._cache[storage_key])
            return None

    def set(self, key: str, value: dict[str, Any], room_id: Optional[str] = None) -> None:
        """Store result in cache associated with room-scoped SHA-256 key."""
        storage_key = self._storage_key(key, room_id)
        with self._lock:
            if storage_key not in self._cache and len(self._cache) >= self._max_entries:
                oldest_key = next(iter(self._cache))
                del self._cache[oldest_key]
                oldest_room = oldest_key[0]
                if oldest_room in self._room_keys:
                    self._room_keys[oldest_room].discard(oldest_key)

            self._cache[storage_key] = dict(value)
            if room_id:
                room_key = str(room_id)
                self._room_keys.setdefault(room_key, set()).add(storage_key)
            logger.debug("Cache store for key=%s room_id=%s", key[:12], room_id)

    def clear_room(self, room_id: str) -> None:
        """Remove cached keys created within a specific room (US-56, FR32)."""
        room_key = str(room_id)
        with self._lock:
            keys = self._room_keys.pop(room_key, None)
            if keys:
                for k in keys:
                    self._cache.pop(k, None)

    def clear(self) -> None:
        """Clear the entire cache."""
        with self._lock:
            self._cache.clear()
            self._room_keys.clear()

    def get_all_keys(self) -> list[str]:
        """Return all stored SHA-256 digest keys for invariant testing."""
        with self._lock:
            return [k[1] for k in self._cache.keys()]


# Default global cache instance
_default_cache = AnalysisCache()


def get_analysis_cache() -> AnalysisCache:
    """Retrieve the global default analysis cache."""
    return _default_cache


def clear_analysis_cache() -> None:
    """Clear all cached entries."""
    _default_cache.clear()
