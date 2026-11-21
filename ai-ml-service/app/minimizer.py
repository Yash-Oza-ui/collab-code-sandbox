"""Deterministic sandbox-only failing-input minimization for US-72.

Candidate and reference programs are never executed in this service. All
executions go through the existing US-70 sandbox_batch / US-71 test_batch path.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.failing_input import Budget, FailingInputRequest


@dataclass(frozen=True)
class MinimizationResult:
    input: str
    expected_output: str | None
    actual_output: str | None
    status: str
    reference_used: bool
    rounds: int
    batches: int
    tests_executed: int


def reductions(value: str) -> list[str]:
    """Deterministic largest-chunk-first deletion, then simple scalar changes."""
    candidates: list[str] = []
    seen = {value}

    def offer(v: str) -> None:
        if len(v) < len(value) and v not in seen:
            seen.add(v)
            candidates.append(v)

    for delimiter in ('\n', ' ', ''):
        parts = value.split(delimiter) if delimiter else list(value)
        if len(parts) < 2:
            continue
        count = len(parts)
        size = count // 2
        while size:
            for start in range(0, count, size):
                offer(delimiter.join(parts[:start] + parts[start + size:]))
            size //= 2
    # Replacing a signed integer by a small canonical value can reduce syntax
    # while still preserving a bug; no assumptions are made about input grammar.
    for match in re.finditer(r'(?<![\w.])-?\d+(?![\w.])', value):
        for replacement in ('0', '1', '-1'):
            offer(value[:match.start()] + replacement + value[match.end():])
    return candidates


async def minimize_failure(
    payload: FailingInputRequest,
    budget: Budget,
    *,
    original_input: str,
    failure_type: str,
    expected_output: str | None,
    actual_output: str | None,
    max_rounds: int,
    max_batches: int,
    reference_used: bool | None = None,
) -> MinimizationResult:
    """Keep only strictly shorter inputs producing the original failure kind.

    US-70's Budget and sandbox_batch exclusively manage the existing run cap,
    aggregate deadline, 429 partial responses and one-container batch semantics.
    """
    from app.failing_input import identify_failure, sandbox_batch

    current = original_input
    expected_current = expected_output
    actual_current = actual_output
    has_reference = (payload.reference_solution is not None if reference_used is None else reference_used)
    rounds = batches = tests = 0
    exhausted = budget.exhausted
    limit_reached = False

    while not exhausted and rounds < max_rounds and batches < max_batches:
        proposed = reductions(current)
        if not proposed:
            break
        rounds += 1
        progressed = False
        # Batches are bounded both by US-72's explicit batch cap and by the
        # existing US-70 process budget. Reference and candidate each consume
        # one process per candidate, but each request remains one Docker batch.
        per_case = 2 if has_reference else 1
        max_candidates = budget.remaining // per_case
        if max_candidates == 0:
            exhausted = True
            break
        offset = 0
        while offset < len(proposed) and batches < max_batches:
            if budget.exhausted:
                exhausted = True
                break
            batch_size = min(len(proposed) - offset, max_candidates, 3)
            if batch_size <= 0:
                exhausted = True
                break
            group = proposed[offset:offset + batch_size]
            offset += batch_size
            expected: list[str] | None = None
            valid_positions: list[int] = list(range(len(group)))
            if has_reference:
                # A reference evaluation requires a second batch for the candidate.
                # Never start a reference-only batch at the configured batch limit.
                if batches + 2 > max_batches:
                    limit_reached = True
                    break
                ref_rows, stopped = await sandbox_batch(payload, budget, payload.reference_solution, group)
                batches += 1
                valid_positions = [i for i, r in enumerate(ref_rows)
                                   if r.get('status') == 'pass'
                                   and not r.get('output_truncated')]
                # A refused/partial batch cannot be reliably retried.
                if stopped:
                    exhausted = True
                    break
                if not valid_positions:
                    max_candidates = budget.remaining // per_case
                    continue
                group = [group[i] for i in valid_positions]
                expected = [ref_rows[i].get('stdout', '') for i in valid_positions]
            if not group:
                continue
            # A reference-backed batch is now tested with each authoritative
            # reference result as its expected output, rejecting invalid inputs.
            rows, stopped = await sandbox_batch(payload, budget, payload.code, group,
                                                expected=expected)
            batches += 1
            tests += len(rows)
            for i, row in enumerate(rows):
                if identify_failure(row, has_reference) != failure_type:
                    continue
                candidate = row['input']
                # Keep only genuine shorter inputs and reject truncated stdout.
                if len(candidate) >= len(current) or row.get('output_truncated'):
                    continue
                if failure_type == 'wrong_output' and (
                    not has_reference or row.get('status') != 'wrong_output'
                    or expected is None or i >= len(expected)
                ):
                    continue
                current = candidate
                expected_current = (expected[i] if failure_type == 'wrong_output'
                                    and has_reference and expected is not None else None)
                actual_current = row.get('stdout') if failure_type == 'wrong_output' else None
                progressed = True
                break
            if stopped:
                exhausted = True
                break
            if progressed:
                break
            max_candidates = budget.remaining // per_case
            if max_candidates == 0 and offset < len(proposed):
                exhausted = True
                break
        if progressed:
            continue
        if batches >= max_batches and proposed:
            limit_reached = True
        break

    # Sandbox refusals and configured round/batch caps all exhaust the
    # minimisation budget. Preserve the smallest reproducible input so far.
    if (exhausted or limit_reached
            or (batches >= max_batches and reductions(current))
            or (rounds >= max_rounds and reductions(current))):
        status = 'budget_exhausted'
    else:
        status = 'completed'
    return MinimizationResult(
        input=current, expected_output=expected_current,
        actual_output=actual_current, status=status,
        reference_used=has_reference, rounds=rounds,
        batches=batches, tests_executed=tests,
    )
