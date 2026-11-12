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
) -> str:
    """Call external LLM API routed through the central consent gate (FR12.2, FR35, US-17)."""
    return await call_llm_with_consent(
        prompt=prompt,
        consent=consent,
        ai_enabled=ai_enabled,
        client=client,
        api_key=api_key,
        model=model,
        base_url=base_url,
        timeout_seconds=timeout_seconds,
    )


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
) -> str:
    """Generate a 3-sentence plain prose failure explanation using LLM (FR13.1, FR40.2, US-76).

    Privacy & Data minimization compliance:
    - FR40.2, US-76: Sends ONLY the relevant code section to external AI, never the full file.
    - FR12.2, FR35: If consent is missing/false or ai_enabled is missing/false, fails immediately at the gate without outbound calls.
    - NFR8.2: Never logs raw code, stderr, or prompts.
    """
    # Safe logging of non-sensitive execution metadata
    logger.info(
        "Generating failure explanation: request_id=%s language=%s failure_kind=%s exit_code=%s timed_out=%s oom_killed=%s",
        request_id,
        language,
        failure_kind,
        exit_code,
        timed_out,
        oom_killed,
    )

    # 1. Extract only the relevant code section (FR40.2, US-76)
    if pre_sliced:
        # Caller supplied an already-localized code_snippet (e.g. Task 4 crash explanation).
        # Avoid re-windowing it with a full-file error_line when it already fits within SECTION_MAX_LINES.
        code_lines = code.splitlines() if code else []
        if len(code_lines) <= settings.SECTION_MAX_LINES:
            relevant_code = code
        else:
            relevant_code = "\n".join(code_lines[: settings.SECTION_MAX_LINES])
    else:
        target_error_line = error_line
        if target_error_line is None and stderr:
            target_error_line = extract_error_line_from_stderr(stderr, language=language)

        relevant_code = extract_relevant_section(
            code=code,
            language=language,
            function_name=function_name,
            error_line=target_error_line,
        )

    prompt = build_explanation_prompt(
        language=language,
        failure_kind=failure_kind,
        exit_code=exit_code,
        stderr=stderr,
        timed_out=timed_out,
        oom_killed=oom_killed,
        code=relevant_code,
    )

    raw_output = await call_llm_with_consent(
        prompt=prompt,
        consent=consent,
        ai_enabled=ai_enabled,
        client=llm_client,
        api_key=api_key,
        model=model,
    )

    explanation = enforce_sentence_limit(raw_output, max_sentences=3)
    logger.info("Successfully generated explanation for request_id=%s", request_id)
    return explanation
