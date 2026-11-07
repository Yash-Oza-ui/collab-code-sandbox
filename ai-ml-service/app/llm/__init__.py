"""LLM integration module for AI/ML service (T-16, FR12, FR13, US-17)."""

from app.llm.client import (
    AIDisabledError,
    ConsentMissingError,
    LLMClient,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    call_llm_with_consent,
    get_default_llm_client,
    set_default_llm_client,
)
from app.llm.explainer import (
    clean_markdown,
    enforce_sentence_limit,
    generate_explanation,
    split_sentences,
)
from app.llm.prompts import EXPLAIN_SYSTEM_INSTRUCTION, build_explanation_prompt

__all__ = [
    "AIDisabledError",
    "ConsentMissingError",
    "LLMClient",
    "LLMProviderError",
    "LLMRateLimitError",
    "LLMTimeoutError",
    "call_llm_with_consent",
    "get_default_llm_client",
    "set_default_llm_client",
    "clean_markdown",
    "enforce_sentence_limit",
    "generate_explanation",
    "split_sentences",
    "EXPLAIN_SYSTEM_INSTRUCTION",
    "build_explanation_prompt",
]
