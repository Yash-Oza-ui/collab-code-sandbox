import { useState } from 'react'
import type { ReactNode } from 'react'
import {
  ALL_LANGUAGE_OPTIONS,
  DR1_LANGUAGES,
  executeRun,
  type RunResult,
  type SupportedLanguage,
} from './runCode'

export interface RunPanelProps {
  roomId: string
  getCode: () => string
  onRunStart?: () => void
  onRunComplete?: (result: RunResult) => void
  onLanguageChange?: (language: SupportedLanguage) => void
  disabled?: boolean
  disabledReason?: string
  initialLanguage?: SupportedLanguage
  initialStdin?: string
  executionApiUrl?: string
  fetchFn?: typeof fetch
  className?: string
  children?: ReactNode
}

export default function RunPanel({
  roomId,
  getCode,
  onRunStart,
  onRunComplete,
  onLanguageChange,
  disabled = false,
  disabledReason,
  initialLanguage = 'python',
  initialStdin = '',
  executionApiUrl,
  fetchFn,
  className = '',
  children,
}: RunPanelProps) {
  const [selectedLanguage, setSelectedLanguage] = useState<SupportedLanguage>(initialLanguage)
  const [stdin, setStdin] = useState<string>(initialStdin)
  const [showStdin, setShowStdin] = useState<boolean>(Boolean(initialStdin))
  const [isRunning, setIsRunning] = useState<boolean>(false)
  const [statusMessage, setServerError] = useState<string | null>(null)
  const [statusType, setStatusType] = useState<'idle' | 'running' | 'success' | 'error'>('idle')
  const [lastResult, setLastResult] = useState<RunResult | null>(null)

  function handleLanguageChange(newLang: string) {
    const isSupported = DR1_LANGUAGES.some((l) => l.id === newLang)
    if (!isSupported) {
      setServerError(`Language "${newLang}" is not available yet.`)
      setStatusType('error')
      return
    }

    const lang = newLang as SupportedLanguage
    setSelectedLanguage(lang)
    setServerError(null)
    setStatusType('idle')
    onLanguageChange?.(lang)
  }

  async function handleRun() {
    if (isRunning || disabled) return

    setIsRunning(true)
    setStatusType('running')
    setServerError('Running code...')
    onRunStart?.()

    try {
      const code = getCode()
      const result = await executeRun({
        roomId,
        code,
        language: selectedLanguage,
        stdin,
        executionApiUrl,
        fetchFn,
      })

      setLastResult(result)

      if (result.ok) {
        setStatusType('success')
        if (result.data.exit_code === 0) {
          setServerError(`Execution completed in ${result.data.execution_time_ms.toFixed(1)} ms.`)
        } else {
          setServerError(`Process exited with code ${result.data.exit_code}.`)
        }
      } else {
        setStatusType('error')
        setServerError(result.error)
      }

      onRunComplete?.(result)
    } catch (err) {
      const errorMsg =
        err instanceof Error
          ? err.message
          : 'Execution service is unavailable. Please check that the execution service is running.'
      const errorResult: RunResult = { ok: false, error: errorMsg, code: 'SERVICE_UNAVAILABLE' }
      setStatusType('error')
      setServerError(errorMsg)
      setLastResult(errorResult)
      onRunComplete?.(errorResult)
    } finally {
      setIsRunning(false)
    }
  }

  const runButtonTitle = disabled
    ? disabledReason || 'Run is currently disabled'
    : isRunning
      ? 'Execution in progress...'
      : 'Execute code in sandbox'

  return (
    <div className={`run-panel ${className}`} data-testid="run-panel">
      <div className="run-panel-toolbar" data-testid="run-panel-toolbar">
        <div className="run-panel-controls-left">
          <label htmlFor="language-select" className="run-panel-label">
            Language:
          </label>
          <select
            id="language-select"
            className="language-select"
            data-testid="language-select"
            aria-label="Programming language"
            value={selectedLanguage}
            disabled={isRunning}
            onChange={(e) => handleLanguageChange(e.target.value)}
          >
            <optgroup label="Supported (DR1)">
              {DR1_LANGUAGES.map((lang) => (
                <option key={lang.id} value={lang.id}>
                  {lang.label}
                </option>
              ))}
            </optgroup>
            <optgroup label="Future Languages">
              {ALL_LANGUAGE_OPTIONS.filter((l) => !l.available).map((lang) => (
                <option key={lang.id} value={lang.id} disabled>
                  {lang.label} (not available yet)
                </option>
              ))}
            </optgroup>
          </select>

          <button
            type="button"
            className={`run-button ${isRunning ? 'running' : ''}`}
            data-testid="run-button"
            onClick={handleRun}
            disabled={isRunning || disabled}
            title={runButtonTitle}
            aria-busy={isRunning}
          >
            {isRunning ? (
              <>
                <span className="run-spinner" aria-hidden="true" />
                <span>Running...</span>
              </>
            ) : (
              <>
                <span aria-hidden="true">▶</span>
                <span>Run</span>
              </>
            )}
          </button>

          <button
            type="button"
            className={`stdin-toggle-button ${showStdin ? 'active' : ''}`}
            data-testid="stdin-toggle-button"
            onClick={() => setShowStdin((prev) => !prev)}
            disabled={isRunning}
            aria-expanded={showStdin}
            aria-controls="stdin-section"
            title="Toggle standard input (stdin)"
          >
            <span>Input (stdin)</span>
            <span aria-hidden="true">{showStdin ? ' ▲' : ' ▼'}</span>
          </button>
        </div>

        {statusMessage && (
          <div
            className={`run-status-banner status-${statusType}`}
            data-testid="run-status-banner"
            role={statusType === 'error' ? 'alert' : 'status'}
          >
            {statusType === 'error' && <span aria-hidden="true">⚠️ </span>}
            {statusType === 'success' && <span aria-hidden="true">✓ </span>}
            <span>{statusMessage}</span>
          </div>
        )}
      </div>

      {showStdin && (
        <div id="stdin-section" className="run-stdin-container" data-testid="run-stdin-container">
          <label htmlFor="stdin-input" className="stdin-label">
            Program standard input (stdin):
          </label>
          <textarea
            id="stdin-input"
            className="stdin-textarea"
            data-testid="stdin-textarea"
            placeholder="Type standard input passed to your program..."
            rows={3}
            value={stdin}
            onChange={(e) => setStdin(e.target.value)}
            disabled={isRunning}
          />
        </div>
      )}

      {/* Reserved container for Het's output panel (feature/T-11-run-panel-output) */}
      <div className="run-output-slot" data-testid="run-output-slot">
        {children ? (
          children
        ) : lastResult && lastResult.ok ? (
          <div className="run-output-preview" data-testid="run-output-preview">
            {lastResult.data.stdout && (
              <div className="output-section stdout-section">
                <div className="output-section-header">stdout:</div>
                <pre className="output-text stdout-text" data-testid="run-stdout">
                  {lastResult.data.stdout}
                </pre>
              </div>
            )}
            {lastResult.data.stderr && (
              <div className="output-section stderr-section">
                <div className="output-section-header">stderr:</div>
                <pre className="output-text stderr-text" data-testid="run-stderr">
                  {lastResult.data.stderr}
                </pre>
              </div>
            )}
            {!lastResult.data.stdout && !lastResult.data.stderr && (
              <div className="output-empty">(No output generated)</div>
            )}
          </div>
        ) : null}
      </div>
    </div>
  )
}
