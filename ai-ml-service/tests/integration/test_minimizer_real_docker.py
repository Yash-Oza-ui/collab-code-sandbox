"""US-72 real US-71 Docker batch verification (opt-in integration test).

Requires a running execution-service and INTERNAL_API_TOKEN. No user code runs
on the test host: the made-up program is always sent via /internal/runs.
"""
import os
import uuid

import httpx
import pytest

from app.failing_input import Budget, FailingInputRequest
from app.minimizer import minimize_failure


@pytest.mark.anyio
async def test_real_batch_minimizes_seeded_crash():
    token = os.getenv('INTERNAL_API_TOKEN')
    url = os.getenv('EXECUTION_SERVICE_URL', 'http://execution-service:8082')
    if not token:
        pytest.skip('INTERNAL_API_TOKEN not configured: Docker integration test requires stack')
    try:
        async with httpx.AsyncClient(timeout=2) as client:
            response = await client.get(url.rstrip('/') + '/actuator/health')
        if response.status_code != 200:
            pytest.skip('execution-service is not healthy')
    except httpx.HTTPError:
        pytest.skip('execution-service is not available')

    code = ("import sys\n"
            "text = sys.stdin.read()\n"
            "if 'BUG' in text:\n"
            "    raise RuntimeError('seeded bug')\n"
            "print('ok')\n")
    seed = 'a\nBUG\nb\nc\nd\ne\n'
    payload = FailingInputRequest(
        request_id=str(uuid.uuid4()), analysis_id=str(uuid.uuid4()),
        room_id='Zq8-Lw2Ef6Th0Yx4Rn7JbA', request_type='failing_input',
        language='python', consent=False, ai_enabled=False, code=code,
    )
    result = await minimize_failure(
        payload, Budget(), original_input=seed, failure_type='crash',
        expected_output=None, actual_output=None,
        max_rounds=8, max_batches=12,
    )
    assert result.input != seed
    assert len(result.input) < len(seed)
    assert 'BUG' in result.input
    assert result.batches > 0
