// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it } from 'vitest'
import RunOutputPanel from './RunOutputPanel'
import type { RunResponseData } from './runCode'

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

const base: RunResponseData = {
  request_id: '0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10',
  room_id: 'room-1',
  language: 'python',
  stdout: 'hello\n',
  stderr: '',
  exit_code: 0,
  execution_time_ms: 38,
  peak_memory_mb: 7.94,
  timed_out: false,
  oom_killed: false,
  failure_kind: null,
  output_truncated: false,
  message: null,
  compile_time_ms: null,
  runner: { member_id: 'm_7a3c9f', display_name: 'Ada' },
  ran_at: '2026-11-01T10:20:05.087Z',
}

describe('RunOutputPanel (T-11 / US-08)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null

  async function render(result: RunResponseData) {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => root?.render(<RunOutputPanel result={result} />))
    return container
  }

  afterEach(async () => {
    if (root) await act(async () => root?.unmount())
    container?.remove()
    root = null
    container = null
  })

  const text = (c: HTMLElement, id: string) => c.querySelector(`[data-testid="${id}"]`)?.textContent

  it('shows stdout, the runner name and the run statistics for a successful run', async () => {
    const c = await render(base)
    expect(text(c, 'run-stdout')).toBe('hello\n')
    expect(text(c, 'run-output-runner')).toContain('Run by Ada')
    expect(text(c, 'run-output-outcome')).toBe('Exited with code 0')
    expect(text(c, 'run-output-stats')).toBe('38 ms, 7.9 MB')
    expect(c.querySelector('[data-testid="run-stderr"]')).toBeNull()
  })

  it('shows stderr and the exit code for a failed run, and never renders a member id', async () => {
    const c = await render({ ...base, stdout: '', stderr: 'Traceback...\n', exit_code: 1 })
    expect(text(c, 'run-stderr')).toBe('Traceback...\n')
    expect(text(c, 'run-output-outcome')).toBe('Exited with code 1')
    expect(c.textContent).not.toContain('m_7a3c9f')
  })

  it('reports a timeout, an out-of-memory kill and truncated output', async () => {
    let c = await render({ ...base, timed_out: true, exit_code: -1 })
    expect(text(c, 'run-output-outcome')).toBe('Timed out')
    await act(async () => root?.unmount())
    c.remove()

    c = await render({ ...base, oom_killed: true, exit_code: 137, output_truncated: true })
    expect(text(c, 'run-output-outcome')).toBe('Out of memory')
    expect(c.querySelector('[data-testid="run-output-truncated"]')).not.toBeNull()
  })

  it('says so when the program printed nothing', async () => {
    const c = await render({ ...base, stdout: '' })
    expect(c.textContent).toContain('(No output generated)')
  })

  it('renders output as text, not as HTML', async () => {
    const c = await render({ ...base, stdout: '<img src=x onerror=alert(1)>' })
    expect(c.querySelector('img')).toBeNull()
    expect(text(c, 'run-stdout')).toBe('<img src=x onerror=alert(1)>')
  })
})
