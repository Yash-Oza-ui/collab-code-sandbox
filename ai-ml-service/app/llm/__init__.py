"""LLM integration module for AI/ML service (T-16, FR12, FR13, US-17, US-22, US-76)."""

from app.llm.client import (
    AIDisabledError,
    ConsentMissingError,
    LLMClient,
    LLMProviderError,
    LLMRateLimitError,
    LLMTimeoutError,
    RoomRateLimitedError,
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
from app.section import extract_error_line_from_stderr, extract_relevant_section

__all__ = [
    "AIDisabledError",
    "ConsentMissingError",
    "LLMClient",
    "LLMProviderError",
    "LLMRateLimitError",
    "LLMTimeoutError",
    "RoomRateLimitedError",
    "call_llm_with_consent",
    "get_default_llm_client",
    "set_default_llm_client",
    "clean_markdown",
    "enforce_sentence_limit",
    "generate_explanation",
    "split_sentences",
    "EXPLAIN_SYSTEM_INSTRUCTION",
    "build_explanation_prompt",
    "extract_relevant_section",
    "extract_error_line_from_stderr",
]
