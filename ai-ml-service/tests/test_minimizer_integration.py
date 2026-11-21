"""US-72 FastAPI regression tests: all sandbox executions are mocked."""
from unittest.mock import AsyncMock

from fastapi.testclient import TestClient
from app.main import app
from app import failing_input as target

ROOM = 'Zq8-Lw2Ef6Th0Yx4Rn7JbA'
RID = '3e4f5a6b-7c8d-9e0f-1a2b-3c4d5e6f7a8b'
AID = '4f5a6b7c-8d9e-0f1a-2b3c-4d5e6f7a8b9c'


def payload():
    return dict(request_id=RID, room_id=ROOM, analysis_id=AID,
                request_type='failing_input', language='python',
                consent=False, ai_enabled=False, code='print(1)')


def test_us72_reduces_seeded_crash_through_batch_with_consent_off(monkeypatch):
    seed = 'alpha\nBUG\nbeta\ngamma\ndelta\n'
    monkeypatch.setattr(target, 'edge_cases', lambda _: ([seed], []))
    calls = []
    async def fake_batch(p, budget, code, inputs, **kwargs):
        calls.append((code, list(inputs)))
        assert len(inputs) <= budget.remaining
        rows = [dict(input=inp, status='crash' if 'BUG' in inp else 'pass',
                     exit_code=1 if 'BUG' in inp else 0, stdout='',
                     execution_time_ms=3) for inp in inputs]
        budget.record(len(rows))
        return rows, False
    monkeypatch.setattr(target, 'sandbox_batch', fake_batch)
    forbidden = AsyncMock(side_effect=AssertionError('LLM should not run'))
    monkeypatch.setattr(target.chain, 'answer', forbidden)
    response = TestClient(app).post('/v1/analyses/failing-input', json=payload())
    assert response.status_code == 200, response.text
    data = response.json()
    assert data['failure_found'] and data['failure_type'] == 'crash'
    assert data['original_input'] == seed
    assert len(data['minimized_input']) < len(seed)
    assert 'BUG' in data['minimized_input']
    assert data['test_case']['input'] == data['minimized_input']
    assert data['minimized_size'] == len(data['minimized_input'])
    assert data['original_size'] == len(seed)
    assert data['reference_used'] is False
    assert data['ai_step']['error_code'] == 'CONSENT_MISSING'
    assert len(calls) > 1
    forbidden.assert_not_called()


def test_us72_budget_refusal_keeps_original(monkeypatch):
    seed = 'start\nBUG\nend\n'
    monkeypatch.setattr(target, 'edge_cases', lambda _: ([seed], []))
    calls = 0
    async def fake_batch(p, budget, code, inputs, **kwargs):
        nonlocal calls
        calls += 1
        if calls > 1:
            budget.exhausted = True
            return [], True
        budget.record(1)
        return [dict(input=seed, status='crash', exit_code=1,
                     execution_time_ms=2, stdout='')], False
    monkeypatch.setattr(target, 'sandbox_batch', fake_batch)
    response = TestClient(app).post('/v1/analyses/failing-input', json=payload())
    assert response.status_code == 200, response.text
    data = response.json()
    assert data['minimization_status'] == 'budget_exhausted'
    assert data['minimized_input'] == seed
    assert data['test_case']['input'] == seed


def test_us72_batch_limit_after_success_is_not_reported_completed(monkeypatch):
    """One accepted reduction at the final allowed batch is still a partial minimisation."""
    from app.minimizer import minimize_failure
    import asyncio

    async def fake_batch(p, budget, code, inputs, **kwargs):
        rows = [dict(input=value, status='crash' if 'BUG' in value else 'pass',
                     exit_code=1 if 'BUG' in value else 0, stdout='') for value in inputs]
        budget.record(len(rows))
        return rows, False

    monkeypatch.setattr(target, 'sandbox_batch', fake_batch)
    result = asyncio.run(minimize_failure(
        target.FailingInputRequest(**payload()), target.Budget(),
        original_input='a\nBUG\nb\nc\nd\n', failure_type='crash',
        expected_output=None, actual_output=None,
        max_rounds=10, max_batches=1,
    ))
    assert result.batches == 1
    assert 'BUG' in result.input and result.input != 'a\nBUG\nb\nc\nd\n'
    assert result.status == 'budget_exhausted'


def test_us72_no_failure_reports_valid_reference_used(monkeypatch):
    monkeypatch.setenv('INTERNAL_API_TOKEN', 'test-token')
    async def fake_batch(p, budget, code, inputs, **kwargs):
        rows = [dict(input=inp, status='pass', stdout='ok', exit_code=0) for inp in inputs]
        budget.record(len(rows))
        return rows, False
    monkeypatch.setattr(target, 'sandbox_batch', fake_batch)
    data = payload()
    data['reference_solution'] = 'print("ok")'
    response = TestClient(app).post('/v1/analyses/failing-input', json=data,
        headers={'X-Internal-Token': 'test-token'})
    assert response.status_code == 200, response.text
    assert response.json()['failure_found'] is False
    assert response.json()['reference_used'] is True


def test_us72_reference_crash_keeps_expected_output_null(monkeypatch):
    monkeypatch.setenv('INTERNAL_API_TOKEN', 'test-token')
    seed = 'alpha\\nBUG\\nbeta\\n'
    monkeypatch.setattr(target, 'edge_cases', lambda _: ([seed], []))
    async def fake_batch(p, budget, code, inputs, **kwargs):
        rows = [dict(input=inp, status='pass' if code == 'print("ok")'
                     else ('crash' if 'BUG' in inp else 'pass'),
                     stdout='ok' if code == 'print("ok")' else '',
                     exit_code=0 if code == 'print("ok")' else (1 if 'BUG' in inp else 0))
                for inp in inputs]
        budget.record(len(rows))
        return rows, False
    monkeypatch.setattr(target, 'sandbox_batch', fake_batch)
    data = payload()
    data['reference_solution'] = 'print("ok")'
    response = TestClient(app).post('/v1/analyses/failing-input', json=data,
        headers={'X-Internal-Token': 'test-token'})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['failure_type'] == 'crash'
    assert body['expected_output'] is None
    assert body['test_case']['expected_output'] is None
    assert body['reference_used'] is True
