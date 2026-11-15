// @vitest-environment happy-dom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RunPanel from './RunPanel'
import type { RunResult } from './runCode'

;(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true

describe('RunPanel component (T-11)', () => {
  let container: HTMLDivElement | null = null
  let root: ReturnType<typeof createRoot> | null = null

  beforeEach(() => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount())
      root = null
    }
    container?.remove()
    container = null
  })

  const defaultProps = {
    roomId: 'test-room-1',
    getCode: () => 'print("hello")',
  }

  it('renders the language picker with all 5 DR1 languages and future disabled options', async () => {
    await act(async () => {
      root?.render(<RunPanel {...defaultProps} />)
    })

    const select = container!.querySelector<HTMLSelectElement>('[data-testid="language-select"]')
    expect(select).not.toBeNull()
    expect(select!.value).toBe('python')

    const options = Array.from(select!.querySelectorAll('option'))
    const optionValues = options.map((opt) => opt.value)

    expect(optionValues).toContain('python')
    expect(optionValues).toContain('javascript')
    expect(optionValues).toContain('c')
    expect(optionValues).toContain('cpp')
    expect(optionValues).toContain('java')

    const rustOption = options.find((opt) => opt.value === 'rust')
    expect(rustOption).toBeDefined()
    expect(rustOption?.textContent).toContain('not available yet')
    expect(rustOption?.disabled).toBe(true)
  })

  it('triggers onLanguageChange when selecting a different supported language', async () => {
    const onLanguageChange = vi.fn()
    await act(async () => {
      root?.render(<RunPanel {...defaultProps} onLanguageChange={onLanguageChange} />)
    })

    const select = container!.querySelector<HTMLSelectElement>('[data-testid="language-select"]')!
    await act(async () => {
      select.value = 'javascript'
      select.dispatchEvent(new Event('change', { bubbles: true }))
    })

    expect(onLanguageChange).toHaveBeenCalledWith('javascript')
    expect(select.value).toBe('javascript')
  })

  it('toggles the stdin input container and updates input value', async () => {
    await act(async () => {
      root?.render(<RunPanel {...defaultProps} />)
    })

    expect(container!.querySelector('[data-testid="run-stdin-container"]')).toBeNull()

    const toggleBtn = container!.querySelector<HTMLButtonElement>('[data-testid="stdin-toggle-button"]')!
    await act(async () => {
      toggleBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    const textarea = container!.querySelector<HTMLTextAreaElement>('[data-testid="stdin-textarea"]')
    expect(textarea).not.toBeNull()
    expect(textarea!.value).toBe('')

    await act(async () => {
      textarea!.value = 'custom stdin test'
      textarea!.dispatchEvent(new Event('change', { bubbles: true }))
    })
    expect(textarea!.value).toBe('custom stdin test')

    // Toggle off
    await act(async () => {
      toggleBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
    expect(container!.querySelector('[data-testid="run-stdin-container"]')).toBeNull()
  })

  it('disables the Run button when disabled prop is true (e.g. for Observers in US-27)', async () => {
    await act(async () => {
      root?.render(<RunPanel {...defaultProps} disabled={true} disabledReason="Observers cannot run code" />)
    })

    const runBtn = container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')!
    expect(runBtn.disabled).toBe(true)
    expect(runBtn.title).toBe('Observers cannot run code')
  })

  it('calls fetch with code and stdin and displays running state and completion', async () => {
    const mockResponse = {
      request_id: '123',
      room_id: 'test-room-1',
      language: 'python',
      stdout: 'hello output\n',
      stderr: '',
      exit_code: 0,
      execution_time_ms: 10.5,
      peak_memory_mb: 2.1,
      timed_out: false,
      oom_killed: false,
      failure_kind: null,
      output_truncated: false,
      message: null,
      compile_time_ms: null,
    }

    let resolveFetch: (value: unknown) => void = () => {}
    const fetchPromise = new Promise((resolve) => {
      resolveFetch = resolve
    })

    const fetchMock = vi.fn().mockImplementation(() => fetchPromise)
    const onRunStart = vi.fn()
    const onRunComplete = vi.fn()

    await act(async () => {
      root?.render(
        <RunPanel
          {...defaultProps}
          fetchFn={fetchMock as unknown as typeof fetch}
          onRunStart={onRunStart}
          onRunComplete={onRunComplete}
        />,
      )
    })

    const runBtn = container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')!
    expect(runBtn.disabled).toBe(false)

    // Trigger run
    await act(async () => {
      runBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    // While request is pending, button is disabled and shows Running...
    expect(runBtn.disabled).toBe(true)
    expect(runBtn.textContent).toContain('Running...')
    expect(onRunStart).toHaveBeenCalledTimes(1)

    // Resolve fetch
    await act(async () => {
      resolveFetch({
        ok: true,
        status: 200,
        json: async () => mockResponse,
      })
    })

    expect(onRunComplete).toHaveBeenCalled()
    const result = onRunComplete.mock.calls[0][0] as RunResult
    expect(result.ok).toBe(true)
    expect(runBtn.disabled).toBe(false)

    const stdoutEl = container!.querySelector('[data-testid="run-stdout"]')
    expect(stdoutEl).not.toBeNull()
    expect(stdoutEl!.textContent).toBe('hello output\n')
  })

  it('handles execution failure and displays error banner', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => ({
        error: {
          code: 'QUEUE_FULL',
          message: 'Queue is full',
          retry_after_seconds: 5,
        },
      }),
    })

    const onRunComplete = vi.fn()
    await act(async () => {
      root?.render(
        <RunPanel
          {...defaultProps}
          fetchFn={fetchMock as unknown as typeof fetch}
          onRunComplete={onRunComplete}
        />,
      )
    })

    const runBtn = container!.querySelector<HTMLButtonElement>('[data-testid="run-button"]')!
    await act(async () => {
      runBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    expect(onRunComplete).toHaveBeenCalled()
    const banner = container!.querySelector('[data-testid="run-status-banner"]')
    expect(banner).not.toBeNull()
    expect(banner!.textContent).toContain('System is busy')
  })

  it('renders child components in the reserved output slot', async () => {
    await act(async () => {
      root?.render(
        <RunPanel {...defaultProps}>
          <div data-testid="het-output-panel">Custom Output Panel</div>
        </RunPanel>,
      )
    })

    const customPanel = container!.querySelector('[data-testid="het-output-panel"]')
    expect(customPanel).not.toBeNull()
    expect(customPanel!.textContent).toBe('Custom Output Panel')
  })
})
