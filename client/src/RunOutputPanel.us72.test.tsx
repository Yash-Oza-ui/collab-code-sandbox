// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RunOutputPanel from './RunOutputPanel'
import type { RunResponseData } from './runCode'
import type { AnalysisResultMessage } from './awarenessWs'

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const run: RunResponseData = {
  request_id: '0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10',
  room_id: 'Zq8-Lw2Ef6Th0Yx4Rn7JbA',
  language: 'python',
  stdout: '', stderr: '', exit_code: 1, execution_time_ms: 2,
  peak_memory_mb: 9, timed_out: false, oom_killed: false,
  failure_kind: 'crash', output_truncated: false, message: null,
  compile_time_ms: null,
}

const analysis: AnalysisResultMessage = {
  type: 'analysis-result', request_id: run.request_id,
  analysis_id: '4f5a6b7c-8d9e-0f1a-2b3c-4d5e6f7a8b9c',
  room_id: run.room_id, task: 'failing_input', status: 'ok',
  ai_step: { status: 'skipped', error_code: 'CONSENT_MISSING' },
  result: {
    failure_found: true, original_input: 'first\nBUG\nlast\n',
    minimized_input: 'BUG', minimization_status: 'completed',
    failure_type: 'crash', can_save_as_test_case: true,
    test_case: { input: 'BUG', failure_type: 'crash', expected_output: null, language: 'python' },
  }, message: 'Failing input found',
}

describe('US-72 minimised failing input display', () => {
  let root: ReturnType<typeof createRoot> | null = null
  let node: HTMLDivElement | null = null
  afterEach(async () => {
    if (root) await act(async () => root?.unmount())
    node?.remove()
    root = null
    node = null
  })

  it('renders minimisation and saves only the test-case object', async () => {
    node = document.createElement('div')
    document.body.appendChild(node)
    root = createRoot(node)
    const save = vi.fn(async () => undefined)
    await act(async () => root?.render(
      <RunOutputPanel result={run} analysisResult={analysis} onSaveTestCase={save} />,
    ))
    expect(node.querySelector('[data-testid="minimized-input-text"]')?.textContent).toBe('BUG')
    expect(node.textContent).toContain('Original: 15 characters; minimised: 3 characters')
    const button = node.querySelector('[data-testid="save-minimized-test-case"]') as HTMLButtonElement
    expect(button).not.toBeNull()
    await act(async () => button.click())
    expect(save).toHaveBeenCalledWith({
      input: 'BUG', failure_type: 'crash', expected_output: null, language: 'python',
    })
    expect(node.textContent).toContain('Saved as test case')
  })
})
