"""LLM-backed failure explainer logic (FR13.1, T-16).

Handles LLM communication, retry/timeout handling, response normalization,
and programmatic sentence limit enforcement.
"""

import json
import logging
import re
from typing import Optional
import httpx

from app.config import settings
from app.llm.prompts import build_explanation_prompt

logger = logging.getLogger(__name__)


class LLMProviderError(Exception):
    """Raised when an external LLM provider returns an error or invalid payload."""

    def __init__(
        self,
        message: str,
        status_code: int = 502,
        code: str = "PROVIDER_ERROR",
        retry_after_seconds: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.code = code
        self.retry_after_seconds = retry_after_seconds


class LLMTimeoutError(LLMProviderError):
    """Raised when upstream LLM provider times out."""

    def __init__(
        self,
        message: str = "Upstream LLM provider timed out while generating explanation.",
    ) -> None:
        super().__init__(
            message=message,
            status_code=504,
            code="PROVIDER_TIMEOUT",
            retry_after_seconds=None,
        )


def clean_markdown(text: str) -> str:
    """Strip markdown formatting (backticks, bold/italics asterisks, headers, bullet points)."""
    # Remove markdown bold/italics: **text** or *text* or __text__ or _text_
    cleaned = re.sub(r"\*{1,3}(.*?)\*{1,3}", r"\1", text)
    cleaned = re.sub(r"_{1,3}(.*?)_{1,3}", r"\1", cleaned)
    # Remove inline backticks
    cleaned = re.sub(r"`(.*?)`", r"\1", cleaned)
    # Remove markdown headers and hashes
    cleaned = re.sub(r"#+", "", cleaned)
    # Remove bullet markers: '-', '*', '+' preceded by start of line or sentence punctuation
    cleaned = re.sub(r"(?:^|(?<=[.!?\n]))\s*[-*+]\s+", " ", cleaned)
    # Remove numbered lists: '1. ' preceded by start of line or sentence punctuation
    cleaned = re.sub(r"(?:^|(?<=[.!?\n]))\s*\d+\.\s+", " ", cleaned)
    # Normalize extra whitespace and newlines into single spaces
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned


def split_sentences(text: str) -> list[str]:
    """Split text into sentences using sentence terminal punctuation."""
    cleaned = text.strip()
    if not cleaned:
        return []
    # Split by '.', '!', '?' when followed by space or end of string
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
    api_key: Optional[str] = None,
    model: Optional[str] = None,
    base_url: Optional[str] = None,
    timeout_seconds: Optional[float] = None,
) -> str:
    """Call external LLM API and return raw response text."""
    key = api_key or settings.LLM_API_KEY
    if not key or not key.strip():
        raise LLMProviderError(
            message="LLM API key is not configured.",
            status_code=502,
            code="PROVIDER_ERROR",
        )

    target_model = model or settings.LLM_MODEL
    target_base_url = base_url or getattr(settings, "LLM_BASE_URL", None)
    timeout = timeout_seconds or getattr(settings, "LLM_TIMEOUT_SECONDS", 10.0)

    is_gemini = not target_base_url and target_model.lower().startswith("gemini")

    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            if is_gemini:
                endpoint = f"https://generativelanguage.googleapis.com/v1beta/models/{target_model}:generateContent"
                headers = {
                    "Content-Type": "application/json",
                    "x-goog-api-key": key,
                }
                body = {
                    "contents": [
                        {
                            "parts": [{"text": prompt}],
                        }
                    ],
                    "generationConfig": {
                        "temperature": 0.2,
                        "maxOutputTokens": 256,
                    },
                }
                response = await client.post(endpoint, headers=headers, json=body)
            else:
                # OpenAI-compatible API format
                endpoint = (
                    f"{target_base_url.rstrip('/')}/chat/completions"
                    if target_base_url
                    else "https://api.openai.com/v1/chat/completions"
                )
                headers = {
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {key}",
                }
                body = {
                    "model": target_model,
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": 0.2,
                    "max_tokens": 256,
                }
                response = await client.post(endpoint, headers=headers, json=body)

    except httpx.TimeoutException as exc:
        logger.warning("LLM API call timed out after %s seconds", timeout)
        raise LLMTimeoutError() from exc
    except httpx.RequestError as exc:
        logger.error("LLM API request error: %s", type(exc).__name__)
        raise LLMProviderError(
            message=f"Network error communicating with AI provider: {type(exc).__name__}",
            status_code=502,
            code="PROVIDER_ERROR",
        ) from exc

    # Handle provider HTTP status codes
    if response.status_code == 429:
        retry_header = response.headers.get("Retry-After")
        retry_seconds = int(retry_header) if retry_header and retry_header.isdigit() else 30
        raise LLMProviderError(
            message="The AI provider returned HTTP 429 (rate limited).",
            status_code=429,
            code="RATE_LIMITED_PROVIDER",
            retry_after_seconds=retry_seconds,
        )

    if response.status_code != 200:
        logger.error("LLM provider returned non-200 status: %d", response.status_code)
        raise LLMProviderError(
            message=f"Upstream AI provider error (status {response.status_code}).",
            status_code=502,
            code="PROVIDER_ERROR",
        )

    try:
        data = response.json()
        if is_gemini:
            candidates = data.get("candidates", [])
            if not candidates:
                raise ValueError("No candidates returned from Gemini API")
            parts = candidates[0].get("content", {}).get("parts", [])
            if not parts or "text" not in parts[0]:
                raise ValueError("No text part in Gemini candidate content")
            return parts[0]["text"]
        else:
            choices = data.get("choices", [])
            if not choices:
                raise ValueError("No choices returned from LLM provider")
            content = choices[0].get("message", {}).get("content", "")
            return content
    except (json.JSONDecodeError, KeyError, IndexError, ValueError) as exc:
        logger.error("Failed to parse LLM provider response: %s", exc)
        raise LLMProviderError(
            message="Upstream AI provider returned an unparseable response.",
            status_code=502,
            code="PROVIDER_ERROR",
        ) from exc


async def generate_explanation(
    language: str,
    failure_kind: Optional[str] = None,
    exit_code: Optional[int] = None,
    stderr: str = "",
    timed_out: bool = False,
    oom_killed: bool = False,
    code: str = "",
    request_id: Optional[str] = None,
    api_key: Optional[str] = None,
    model: Optional[str] = None,
) -> str:
    """Generate a 3-sentence plain prose failure explanation using LLM.

    Privacy compliance (NFR8.2): Never logs raw code or stderr.
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

    prompt = build_explanation_prompt(
        language=language,
        failure_kind=failure_kind,
        exit_code=exit_code,
        stderr=stderr,
        timed_out=timed_out,
        oom_killed=oom_killed,
        code=code,
    )

    raw_output = await call_llm_api(
        prompt=prompt,
        api_key=api_key,
        model=model,
    )

    explanation = enforce_sentence_limit(raw_output, max_sentences=3)
    logger.info("Successfully generated explanation for request_id=%s", request_id)
    return explanation
