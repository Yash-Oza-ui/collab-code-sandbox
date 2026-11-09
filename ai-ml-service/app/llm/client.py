"""Central LLM client and universal consent gate (FR12, US-17).

Enforces that NO code or prompt ever reaches an external AI service
unless user consent is explicitly provided (consent == True).
All outbound LLM calls pass through call_llm_with_consent.
"""

import json
import logging
from typing import Optional
import httpx

from app.config import settings
from app.counters import record_model_call

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
        message: str = "Upstream LLM provider timed out while generating response.",
    ) -> None:
        super().__init__(
            message=message,
            status_code=504,
            code="PROVIDER_TIMEOUT",
            retry_after_seconds=None,
        )


class LLMRateLimitError(LLMProviderError):
    """Raised when upstream LLM provider returns HTTP 429 rate limit."""

    def __init__(
        self,
        message: str = "The AI provider returned HTTP 429 (rate limited).",
        retry_after_seconds: Optional[int] = 30,
    ) -> None:
        super().__init__(
            message=message,
            status_code=429,
            code="RATE_LIMITED_PROVIDER",
            retry_after_seconds=retry_after_seconds,
        )


class ConsentMissingError(Exception):
    """Raised when an LLM call is attempted without explicit user consent (FR12.2)."""

    def __init__(
        self,
        message: str = "User consent is required before code or execution context can be sent to external AI.",
        status_code: int = 403,
        code: str = "CONSENT_MISSING",
        retry_after_seconds: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.code = code
        self.retry_after_seconds = retry_after_seconds


class AIDisabledError(Exception):
    """Raised when an LLM call is attempted while Host AI switch is disabled or missing (FR35, T-18 v2)."""

    def __init__(
        self,
        message: str = "Host AI switch is disabled for this room/session.",
        status_code: int = 403,
        code: str = "AI_DISABLED",
        retry_after_seconds: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.code = code
        self.retry_after_seconds = retry_after_seconds


class LLMClient:
    """Outbound HTTP client communicating with external LLM APIs (Gemini / OpenAI)."""

    def __init__(self) -> None:
        self.call_count: int = 0

    async def complete(
        self,
        prompt: str,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
        base_url: Optional[str] = None,
        timeout_seconds: Optional[float] = None,
        room_id: Optional[str] = None,
    ) -> str:
        """Execute outbound HTTP call to LLM provider."""
        self.call_count += 1

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

        if response.status_code == 429:
            retry_header = response.headers.get("Retry-After")
            retry_seconds = int(retry_header) if retry_header and retry_header.isdigit() else 30
            raise LLMRateLimitError(
                message="The AI provider returned HTTP 429 (rate limited).",
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
            tokens = 0
            if is_gemini:
                candidates = data.get("candidates", [])
                if not candidates:
                    raise ValueError("No candidates returned from Gemini API")
                parts = candidates[0].get("content", {}).get("parts", [])
                if not parts or "text" not in parts[0]:
                    raise ValueError("No text part in Gemini candidate content")
                usage_meta = data.get("usageMetadata", {})
                tokens = usage_meta.get("totalTokenCount", 0)
                if not tokens:
                    tokens = usage_meta.get("promptTokenCount", 0) + usage_meta.get("candidatesTokenCount", 0)
                output_text = parts[0]["text"]
            else:
                choices = data.get("choices", [])
                if not choices:
                    raise ValueError("No choices returned from LLM provider")
                output_text = choices[0].get("message", {}).get("content", "")
                usage_meta = data.get("usage", {})
                tokens = usage_meta.get("total_tokens", 0)
                if not tokens:
                    tokens = usage_meta.get("prompt_tokens", 0) + usage_meta.get("completion_tokens", 0)

            # Record model call with tokens extracted from provider response (FR40.1, US-75)
            record_model_call(room_id=room_id or "default", tokens=tokens)
            return output_text
        except (json.JSONDecodeError, KeyError, IndexError, ValueError) as exc:
            logger.error("Failed to parse LLM provider response: %s", exc)
            raise LLMProviderError(
                message="Upstream AI provider returned an unparseable response.",
                status_code=502,
                code="PROVIDER_ERROR",
            ) from exc


# Global default client instance
_default_llm_client: LLMClient = LLMClient()


def get_default_llm_client() -> LLMClient:
    """Retrieve the current default LLM client."""
    return _default_llm_client


def set_default_llm_client(client: LLMClient) -> None:
    """Configure the default LLM client (useful for dependency injection in tests)."""
    global _default_llm_client
    _default_llm_client = client


async def call_llm_with_consent(
    prompt: str,
    consent: Optional[bool] = None,
    ai_enabled: Optional[bool] = None,
    *,
    client: Optional[LLMClient] = None,
    api_key: Optional[str] = None,
    model: Optional[str] = None,
    base_url: Optional[str] = None,
    timeout_seconds: Optional[float] = None,
    room_id: Optional[str] = None,
) -> str:
    """Universal consent and Host AI switch gate through which every outbound LLM call must pass (FR12.2, FR35, US-17).

    Guarantees:
    1. If consent is missing (None) or False:
       - The LLM client is never invoked (0 outbound calls made).
       - No prompts or source code are logged (NFR8.2).
       - ConsentMissingError is raised immediately (HTTP 403 CONSENT_MISSING).
    2. If Host AI switch (ai_enabled) is missing (None) or False:
       - The LLM client is never invoked (0 outbound calls made).
       - AIDisabledError is raised immediately (HTTP 403 AI_DISABLED).
    3. External AI call is permitted ONLY when BOTH consent == True AND ai_enabled == True.
    """
    if consent is not True:
        # Safe logging: strictly no prompt or code logged (NFR8.2)
        logger.info("Blocked external AI call: consent is missing or false (FR12.2)")
        raise ConsentMissingError(
            message="User consent is required before code or execution context can be sent to external AI.",
            status_code=403,
            code="CONSENT_MISSING",
        )

    if ai_enabled is not True:
        # Safe logging: strictly no prompt or code logged (NFR8.2)
        logger.info("Blocked external AI call: Host AI switch is disabled or missing (FR35)")
        raise AIDisabledError(
            message="Host AI switch is disabled for this room/session.",
            status_code=403,
            code="AI_DISABLED",
        )

    target_client = client or get_default_llm_client()
    return await target_client.complete(
        prompt=prompt,
        api_key=api_key,
        model=model,
        base_url=base_url,
        timeout_seconds=timeout_seconds,
        room_id=room_id,
    )
