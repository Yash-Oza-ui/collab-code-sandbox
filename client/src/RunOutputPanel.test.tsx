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

  describe('US-50 / FR26 run result labeling', () => {
    const pairResult: RunResponseData = {
      request_id: '0b9d2f3e-7a41-4c58-9e1b-5d3c8a6f2e10',
      room_id: 'Zq8-Lw2Ef6Th0Yx4Rn7JbA',
      language: 'python',
      stdout: 'hello\n',
      stderr: '',
      exit_code: 0,
      execution_time_ms: 38,
      peak_memory_mb: 7.9,
      timed_out: false,
      oom_killed: false,
      failure_kind: null,
      output_truncated: false,
      message: null,
      compile_time_ms: null,
      runner: {
        member_id: 'm_7a3c9f',
        display_name: 'Ada',
      },
      ran_at: '2026-11-01T10:20:05.087Z',
      visibility: 'room',
      saved_versions: [],
      failure: null,
    }

    const openFailureResult: RunResponseData = {
      request_id: 'd7a2f5c8-1e39-4b60-a4c2-9f8e1d7b3a06',
      room_id: 'k3Yc1t9Qx7mBvA2nR0sLpQ',
      language: 'python',
      stdout: '',
      stderr:
        'Traceback (most recent call last):\n  File "main.py", line 2, in <module>\n    print(add(2, "3"))\n  File "helper.py", line 2, in add\n    return a + b\nTypeError: unsupported operand type(s) for +: \'int\' and \'str\'\n',
      exit_code: 1,
      execution_time_ms: 41,
      peak_memory_mb: 8.4,
      timed_out: false,
      oom_killed: false,
      failure_kind: 'non_zero_exit',
      output_truncated: false,
      message: null,
      compile_time_ms: null,
      runner: {
        member_id: 'm_4b19d2',
        display_name: 'Lin',
      },
      ran_at: '2026-11-01T10:15:30.412Z',
      visibility: 'private',
      saved_versions: [
        {
          module_id: 'mod_9Qx7mBvA2nR0',
          name: 'main.py',
          version: 4,
          saved_by: {
            member_id: 'm_7a3c9f',
            display_name: 'Ada',
          },
          saved_at: '2026-11-01T10:12:04.118Z',
        },
        {
          module_id: 'mod_5Lm8Np2Qr4St',
          name: 'helper.py',
          version: 2,
          saved_by: {
            member_id: 'm_4b19d2',
            display_name: 'Lin',
          },
          saved_at: '2026-11-01T10:14:51.733Z',
        },
      ],
      failure: {
        module_id: 'mod_5Lm8Np2Qr4St',
        name: 'helper.py',
        owner: {
          member_id: 'm_7a3c9f',
          display_name: 'Ada',
        },
        last_saved_by: {
          member_id: 'm_4b19d2',
          display_name: 'Lin',
        },
      },
    }

    it('renders Pair-mode result with runner display name, local time, raw timestamp tooltip, and no saved version line', async () => {
      const c = await render(pairResult)
      expect(text(c, 'run-output-runner')).toBe('Run by Ada')
      const timeEl = c.querySelector('[data-testid="run-output-time"]')
      expect(timeEl).not.toBeNull()
      expect(timeEl?.textContent).toMatch(/^ at /)
      expect(timeEl?.getAttribute('title')).toBe('2026-11-01T10:20:05.087Z')
      expect(c.querySelector('[data-testid="run-output-saved-versions"]')).toBeNull()
      expect(c.textContent).not.toContain('m_7a3c9f')
    })

    it('renders Open-mode result with runner, raw timestamp tooltip, and saved version attribution', async () => {
      const c = await render(openFailureResult)
      expect(text(c, 'run-output-runner')).toBe('Run by Lin')
      const timeEl = c.querySelector('[data-testid="run-output-time"]')
      expect(timeEl).not.toBeNull()
      expect(timeEl?.textContent).toMatch(/^ at /)
      expect(timeEl?.getAttribute('title')).toBe('2026-11-01T10:15:30.412Z')

      const savedVersionsEl = c.querySelector('[data-testid="run-output-saved-versions"]')
      expect(savedVersionsEl).not.toBeNull()
      expect(savedVersionsEl?.textContent).toContain('saved version: main.py v4, helper.py v2')

      const mainModuleEl = c.querySelector('[data-testid="saved-version-main.py"]')
      expect(mainModuleEl).not.toBeNull()
      expect(mainModuleEl?.getAttribute('title')).toBe('Saved by Ada at 2026-11-01T10:12:04.118Z')

      const helperModuleEl = c.querySelector('[data-testid="saved-version-helper.py"]')
      expect(helperModuleEl).not.toBeNull()
      expect(helperModuleEl?.getAttribute('title')).toBe('Saved by Lin at 2026-11-01T10:14:51.733Z')

      // Member IDs must never be rendered in user-facing text
      expect(c.textContent).not.toContain('m_4b19d2')
      expect(c.textContent).not.toContain('m_7a3c9f')
    })

    it('renders single saved version cleanly in Open mode', async () => {
      const singleModuleResult: RunResponseData = {
        ...pairResult,
        saved_versions: [
          {
            module_id: 'mod_single',
            name: 'main.py',
            version: 1,
            saved_by: { member_id: 'm_ada', display_name: 'Ada' },
            saved_at: '2026-11-01T10:00:00.000Z',
          },
        ],
      }
      const c = await render(singleModuleResult)
      expect(text(c, 'run-output-saved-versions')).toBe('saved version: main.py v1')
    })

    it('keeps each result labelled with its own runner, timestamp, and saved versions across consecutive runs', async () => {
      // First run: Pair mode run by Ada
      const c = await render(pairResult)
      expect(text(c, 'run-output-runner')).toContain('Run by Ada')
      expect(c.querySelector('[data-testid="run-output-time"]')?.getAttribute('title')).toBe('2026-11-01T10:20:05.087Z')
      expect(c.querySelector('[data-testid="run-output-saved-versions"]')).toBeNull()

      // Subsequent run arrives: Open mode run by Lin
      await act(async () => root?.render(<RunOutputPanel result={openFailureResult} />))
      expect(text(c, 'run-output-runner')).toContain('Run by Lin')
      expect(c.querySelector('[data-testid="run-output-time"]')?.getAttribute('title')).toBe('2026-11-01T10:15:30.412Z')
      expect(text(c, 'run-output-saved-versions')).toBe('saved version: main.py v4, helper.py v2')

      // History/previous run is viewed again: Ada's original labels are intact
      await act(async () => root?.render(<RunOutputPanel result={pairResult} />))
      expect(text(c, 'run-output-runner')).toContain('Run by Ada')
      expect(c.querySelector('[data-testid="run-output-time"]')?.getAttribute('title')).toBe('2026-11-01T10:20:05.087Z')
      expect(c.querySelector('[data-testid="run-output-saved-versions"]')).toBeNull()
    })

    it('displays compile time separately when compile_time_ms is provided', async () => {
      const compileResult: RunResponseData = {
        ...pairResult,
        compile_time_ms: 125,
        execution_time_ms: 45,
        peak_memory_mb: 12.3,
      }
      const c = await render(compileResult)
      expect(text(c, 'run-output-stats')).toBe('125 ms compile, 45 ms, 12.3 MB')
    })

    it('handles run result with timestamp but without runner gracefully', async () => {
      const noRunnerResult: RunResponseData = {
        ...pairResult,
        runner: undefined,
        ran_at: '2026-11-01T10:20:05.087Z',
      }
      const c = await render(noRunnerResult)
      expect(c.querySelector('[data-testid="run-output-runner"]')).toBeNull()
      const timeEl = c.querySelector('[data-testid="run-output-time"]')
      expect(timeEl).not.toBeNull()
      expect(timeEl?.getAttribute('title')).toBe('2026-11-01T10:20:05.087Z')
      expect(timeEl?.textContent).toContain('Ran at')
    })
  })
})
