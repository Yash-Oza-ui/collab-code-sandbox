"""Normalized code cache module (US-67, FR36.4, NFR5.2, NFR8.2, T-18 v2 Decision 11).

Provides SHA-256 hashing of normalized code and in-memory caching of AI suggestions.
Automatically records cache hits via app.counters.record_cache_hit(room_id).
Strict Privacy Invariant (NFR8.2):
Only the 64-character SHA-256 hex digest is used as the cache key.
Raw code is never stored in the cache keys or metadata.
"""

import hashlib
import re
import threading
from typing import Any, Optional

from app.counters import record_cache_hit


def normalize_code(code: str, language: str = "python") -> str:
    """Normalize source code by stripping comments and extraneous whitespace (NFR5.2, Decision 11).

    Preserves semantic indentation for Python.
    """
    lang = language.lower()
    text = code

    # Strip comments based on language
    if lang in ["python"]:
        # Strip triple-quote block strings/comments
        text = re.sub(r'"""[\s\S]*?"""', '', text)
        text = re.sub(r"'''[\s\S]*?'''", '', text)
        # Strip line comments
        text = re.sub(r'#.*$', '', text, flags=re.MULTILINE)
    elif lang in ["cpp", "c", "java", "javascript"]:
        # Strip /* ... */ block comments
        text = re.sub(r'/\*[\s\S]*?\*/', '', text)
        # Strip // line comments
        text = re.sub(r'//.*$', '', text, flags=re.MULTILINE)

    # Normalize lines: strip trailing whitespace, collapse blank lines
    lines = []
    for line in text.splitlines():
        if lang == "python":
            stripped = line.rstrip()
            if stripped:
                lines.append(stripped)
        else:
            stripped = line.strip()
            if stripped:
                lines.append(stripped)

    return "\n".join(lines).strip()


def compute_cache_key(
    code: str,
    language: str,
    request_type: str = "complexity",
    error_signature: str = "",
) -> str:
    """Compute normalized SHA-256 cache key (T-18 v2 Decision 11, NFR8.2).

    Formula:
        cache_key = SHA-256(normalized_code + language + request_type + error_signature)

    Only the 64-character hexadecimal hash is returned and stored.
    """
    normalized = normalize_code(code, language=language)
    hasher = hashlib.sha256()
    hasher.update(normalized.encode("utf-8"))
    hasher.update(b":")
    hasher.update(language.lower().encode("utf-8"))
    hasher.update(b":")
    hasher.update(request_type.lower().encode("utf-8"))
    hasher.update(b":")
    hasher.update(error_signature.encode("utf-8"))
    return hasher.hexdigest()


class AnalysisCache:
    """Thread-safe in-memory cache for analysis results and AI suggestions."""

    def __init__(self, max_entries: int = 1000) -> None:
        self._lock = threading.Lock()
        self._max_entries = max_entries
        self._cache: dict[str, dict[str, Any]] = {}
        # Mapping of room_id to set of cache keys created within that room
        self._room_keys: dict[str, set[str]] = {}

    def get(self, key: str, room_id: Optional[str] = None) -> Optional[dict[str, Any]]:
        """Retrieve cached result by SHA-256 key and record a cache hit if found (FR40.1)."""
        with self._lock:
            if key in self._cache:
                if room_id:
                    record_cache_hit(room_id)
                return dict(self._cache[key])
            return None

    def set(self, key: str, value: dict[str, Any], room_id: Optional[str] = None) -> None:
        """Store result in cache associated with SHA-256 key."""
        with self._lock:
            # Simple eviction if max capacity is exceeded
            if len(self._cache) >= self._max_entries:
                oldest_key = next(iter(self._cache))
                del self._cache[oldest_key]

            self._cache[key] = dict(value)
            if room_id:
                if room_id not in self._room_keys:
                    self._room_keys[room_id] = set()
                self._room_keys[room_id].add(key)

    def clear_room(self, room_id: str) -> None:
        """Remove cached keys created within a specific room (US-56)."""
        with self._lock:
            keys = self._room_keys.pop(room_id, None)
            if keys:
                for k in keys:
                    self._cache.pop(k, None)

    def clear(self) -> None:
        """Clear the entire cache."""
        with self._lock:
            self._cache.clear()
            self._room_keys.clear()


# Default global cache instance
_default_cache = AnalysisCache()


def get_analysis_cache() -> AnalysisCache:
    """Retrieve global default analysis cache."""
    return _default_cache


def clear_analysis_cache() -> None:
    """Clear all cached entries."""
    _default_cache.clear()
