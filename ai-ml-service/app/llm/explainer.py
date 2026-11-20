"""LLM-backed failure explainer logic (FR13.1, T-16, US-17, US-76).

Handles LLM communication, consent gating, retry/timeout handling, response normalization,
programmatic sentence limit enforcement, and relevant code section extraction (FR40.2).
"""

import logging
import re
from typing import Optional

from app.llm.client import (
    AIDisabledError,
    ConsentMissingError,
    LLMClient,
    LLMProviderError,
    LLMTimeoutError,
    call_llm_with_consent,
    get_default_llm_client,
)
from app.config import settings
from app.llm.prompts import build_explanation_prompt
from app.section import extract_error_line_from_stderr, extract_relevant_section

logger = logging.getLogger(__name__)


def clean_markdown(text: str) -> str:
    """Strip markdown formatting (backticks, bold/italics asterisks, headers, bullet points)."""
    cleaned = re.sub(r"\*{1,3}(.*?)\*{1,3}", r"\1", text)
    cleaned = re.sub(r"_{1,3}(.*?)_{1,3}", r"\1", cleaned)
    cleaned = re.sub(r"`(.*?)`", r"\1", cleaned)
    cleaned = re.sub(r"#+", "", cleaned)
    cleaned = re.sub(r"(?:^|(?<=[.!?\n]))\s*[-*+]\s+", " ", cleaned)
    cleaned = re.sub(r"(?:^|(?<=[.!?\n]))\s*\d+\.\s+", " ", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned


def split_sentences(text: str) -> list[str]:
    """Split text into sentences using sentence terminal punctuation."""
    cleaned = text.strip()
    if not cleaned:
        return []
    raw_sentences = re.split(r"(?<=[.!?])\s+", cleaned)
    return [s.strip() for s in raw_sentences if s.strip()]


def enforce_sentence_limit(text: str, max_sentences: int = 3) -> str:
    """Enforce at most max_sentences, trimming any excess (FR13.1)."""
    cleaned = clean_markdown(text)
    sentences = split_sentences(cleaned)
    if not sentences:
        return cleaned

    if len(sentences) <= max_sentences:
        return cleaned

    trimmed = " ".join(sentences[:max_sentences])
    if not trimmed.endswith((".", "!", "?")):
        trimmed += "."
    return trimmed


async def call_llm_api(
    prompt: str,
    consent: Optional[bool] = None,
    ai_enabled: Optional[bool] = None,
    api_key: Optional[str] = None,
    model: Optional[str] = None,
    base_url: Optional[str] = None,
    timeout_seconds: Optional[float] = None,
    client: Optional[LLMClient] = None,
    room_id: Optional[str] = None,
) -> str:
    """Call external LLM API routed through the central consent gate (FR12.2, FR35, US-17)."""
    from app import chain
    chain_req = chain.ChainRequest(
        request_type="explanation",
        code=prompt,
        language="python",
        prompt=prompt,
        room_id=room_id,
        consent=consent,
        ai_enabled=ai_enabled,
        client=client,
        api_key=api_key,
        model=model,
        base_url=base_url,
        timeout_seconds=timeout_seconds,
        rules_first=False,
    )
    res = await chain.answer(chain_req)
    return res.answer or ""


async def generate_explanation(
    language: str,
    consent: Optional[bool] = None,
    ai_enabled: Optional[bool] = None,
    failure_kind: Optional[str] = None,
    exit_code: Optional[int] = None,
    stderr: str = "",
    timed_out: bool = False,
    oom_killed: bool = False,
    code: str = "",
    request_id: Optional[str] = None,
    api_key: Optional[str] = None,
    model: Optional[str] = None,
    llm_client: Optional[LLMClient] = None,
    error_line: Optional[int] = None,
    function_name: Optional[str] = None,
    pre_sliced: bool = False,
    room_id: Optional[str] = None,
) -> str:
    """Generate a 3-sentence plain prose failure explanation using central method chain (FR13.1, FR40.2, FR40.3, US-76, US-77)."""
    from app.chain import ChainRequest, answer
    from app.llm.client import AIDisabledError, ConsentMissingError, RoomRateLimitedError

    req = ChainRequest(
        request_type="explain",
        language=language,
        code=code,
        consent=consent,
        ai_enabled=ai_enabled,
        failure_kind=failure_kind,
        exit_code=exit_code,
        error_output=stderr,
        timed_out=timed_out,
        oom_killed=oom_killed,
        request_id=request_id,
        client=llm_client,
        error_line=error_line,
        function_name=function_name,
        pre_sliced=pre_sliced,
        room_id=room_id,
        rules_first=False,
        reraise_ai_errors=True,
    )
    resp = await answer(req)
    if resp.status == "skipped" and resp.error_code:
        if resp.error_code == "CONSENT_MISSING":
            raise ConsentMissingError()
        elif resp.error_code == "AI_DISABLED":
            raise AIDisabledError()
        elif resp.error_code == "COST_CAP_REACHED":
            from app.chain import CostCapReachedError
            if CostCapReachedError is not None:
                raise CostCapReachedError()
        elif resp.error_code == "ROOM_RATE_LIMITED":
            raise RoomRateLimitedError()
    return resp.answer or ""
