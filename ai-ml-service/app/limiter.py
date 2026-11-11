"""Per-room hourly rate limiter for AI requests (NFR5.3, US-22, DR3).

Limits outbound LLM calls per room within a rolling 1-hour window using
configurable limit `settings.ROOM_HOURLY_LIMIT`.
"""

import logging
import threading
import time
from typing import Optional

from app.config import settings

logger = logging.getLogger(__name__)


class RoomRateLimiter:
    """Thread-safe sliding-window rate limiter per room (NFR5.3, US-22)."""

    def __init__(self, window_seconds: float = 3600.0) -> None:
        self._lock = threading.Lock()
        self._window_seconds = window_seconds
        self._room_timestamps: dict[str, list[float]] = {}

    def _cleanup_unlocked(self, room_id: str, now: float) -> list[float]:
        """Prune timestamps older than the rolling window."""
        cutoff = now - self._window_seconds
        valid = [t for t in self._room_timestamps.get(room_id, []) if t > cutoff]
        self._room_timestamps[room_id] = valid
        return valid

    def is_rate_limited(
        self,
        room_id: Optional[str],
        limit: Optional[int] = None,
        now: Optional[float] = None,
    ) -> bool:
        """Check whether the room has reached its hourly limit."""
        if not room_id:
            return False

        max_allowed = limit if limit is not None else settings.ROOM_HOURLY_LIMIT
        if max_allowed <= 0:
            return True

        current_time = now if now is not None else time.time()
        with self._lock:
            valid = self._cleanup_unlocked(room_id, current_time)
            return len(valid) >= max_allowed

    def record_call(
        self,
        room_id: Optional[str],
        now: Optional[float] = None,
    ) -> None:
        """Record an outbound LLM call timestamp against the room quota."""
        if not room_id:
            return

        current_time = now if now is not None else time.time()
        with self._lock:
            self._cleanup_unlocked(room_id, current_time)
            if room_id not in self._room_timestamps:
                self._room_timestamps[room_id] = []
            self._room_timestamps[room_id].append(current_time)

    def get_count(
        self,
        room_id: Optional[str],
        now: Optional[float] = None,
    ) -> int:
        """Return active call count within the current window for a room."""
        if not room_id:
            return 0

        current_time = now if now is not None else time.time()
        with self._lock:
            valid = self._cleanup_unlocked(room_id, current_time)
            return len(valid)

    def clear_room(self, room_id: str) -> None:
        """Clear rate limit records for a room (US-56)."""
        with self._lock:
            self._room_timestamps.pop(room_id, None)

    def clear(self) -> None:
        """Clear all rate limit records."""
        with self._lock:
            self._room_timestamps.clear()


# Default global limiter instance
_default_limiter = RoomRateLimiter()


def get_room_rate_limiter() -> RoomRateLimiter:
    """Retrieve the global default room rate limiter."""
    return _default_limiter


def clear_room_rate_limiter() -> None:
    """Clear all rate limiting records globally."""
    _default_limiter.clear()
