"""Prompt templates for LLM failure explanation (FR13.1, T-16).

Drafted by Prince for feature/T-16-llm-explanation-prototype.
Adheres to docs/ai-ml-contract.md §4.3 and §11.
"""

from typing import Optional

EXPLAIN_SYSTEM_INSTRUCTION = (
    "You are an expert programming assistant in a collaborative code execution sandbox. "
    "A user's code execution failed. Provide a short, plain-language explanation "
    "describing the likely cause of the failure and how to resolve it.\n\n"
    "STRICT CONSTRAINTS:\n"
    "1. Your explanation MUST be at most three sentences.\n"
    "2. Write in plain prose only. Do NOT use markdown formatting (no bold asterisks, backticks, bullet lists, or headers).\n"
    "3. Be direct, clear, and actionable: state what caused the error and suggest a fix.\n"
    "4. Do NOT include greetings, conversational filler, or sign-offs."
)


def build_explanation_prompt(
    language: str,
    failure_kind: Optional[str] = None,
    exit_code: Optional[int] = None,
    stderr: str = "",
    timed_out: bool = False,
    oom_killed: bool = False,
    code: str = "",
) -> str:
    """Build the prompt for explaining a failed execution.

    Args:
        language: Programming language (cpp, python, c, java, javascript).
        failure_kind: Failure category ('compile_error', 'non_zero_exit', 'timeout', or None).
        exit_code: Exit status integer from execution.
        stderr: Standard error text.
        timed_out: Boolean indicating if wall time limit was exceeded.
        oom_killed: Boolean indicating if memory limit was exceeded.
        code: Source code that was executed.

    Returns:
        Structured text prompt ready for LLM submission.
    """
    metadata_lines = [
        f"- Language: {language}",
        f"- Failure Kind: {failure_kind or 'unspecified'}",
        f"- Exit Code: {exit_code if exit_code is not None else 'unknown'}",
        f"- Timed Out: {'yes' if timed_out else 'no'}",
        f"- Out of Memory (OOM): {'yes' if oom_killed else 'no'}",
    ]
    metadata_str = "\n".join(metadata_lines)

    stderr_text = stderr.strip() if stderr else "(none)"
    code_text = code.strip() if code else "(none)"

    return (
        f"{EXPLAIN_SYSTEM_INSTRUCTION}\n\n"
        f"--- Execution Context ---\n"
        f"{metadata_str}\n\n"
        f"--- Standard Error Output ---\n"
        f"{stderr_text}\n\n"
        f"--- Source Code ---\n"
        f"{code_text}\n\n"
        f"Explanation (at most 3 sentences in plain prose):"
    )
