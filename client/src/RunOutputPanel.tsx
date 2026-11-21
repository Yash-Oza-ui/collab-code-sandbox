import { useState } from 'react'
import type { RunResponseData } from './runCode'
import type { AnalysisResultMessage } from './awarenessWs'

export interface SavedTestCandidate {
  input: string
  failure_type: 'wrong_output' | 'crash' | 'timeout'
  expected_output: string | null
  language: 'python' | 'cpp' | 'c' | 'java' | 'javascript'
}

export interface RunOutputPanelProps {
  result: RunResponseData
  analysisResult?: AnalysisResultMessage | null
  onSaveTestCase?: (value: SavedTestCandidate) => Promise<void>
  currentViewerMemberId?: string
}

function formatRanAt(ranAt: string): string {
  const date = new Date(ranAt)
  return Number.isNaN(date.getTime()) ? ranAt : date.toLocaleTimeString()
}

/**
 * Output of one run (T-11, US-08, US-50 / FR26).
 *
 * Shows the result for the person who pressed Run and, through the `run-result` frame,
 * for every participant in the room. Labels every shown result with runner display name
 * and local time (with raw timestamp in tooltip). In Open mode, labels the saved
 * versions that were run. Member IDs are never rendered.
 */
export default function RunOutputPanel({ result, analysisResult, onSaveTestCase, currentViewerMemberId }: RunOutputPanelProps) {
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const analysis = analysisResult?.task === 'failing_input' ? analysisResult.result : undefined
  const originalInput = typeof analysis?.original_input === 'string' ? analysis.original_input : null
  const minimizedInput = typeof analysis?.minimized_input === 'string' ? analysis.minimized_input : null
  const savedCandidate = analysis?.test_case
  const isCandidate = (value: unknown): value is SavedTestCandidate => {
    if (!value || typeof value !== 'object') return false
    const v = value as Record<string, unknown>
    return typeof v.input === 'string' &&
      (v.failure_type === 'wrong_output' || v.failure_type === 'crash' || v.failure_type === 'timeout') &&
      (v.expected_output === null || typeof v.expected_output === 'string') &&
      (v.language === 'python' || v.language === 'cpp' || v.language === 'c' ||
        v.language === 'java' || v.language === 'javascript')
  }
  const ownsRun = Boolean(currentViewerMemberId && result.runner?.member_id === currentViewerMemberId)
  const canSave = ownsRun && analysis?.can_save_as_test_case === true && isCandidate(savedCandidate)
  const saveTest = async () => {
    if (!onSaveTestCase || !isCandidate(savedCandidate)) return
    setSaveState('saving')
    try {
      await onSaveTestCase(savedCandidate)
      setSaveState('saved')
    } catch {
      setSaveState('error')
    }
  }
  const runnerName = result.runner?.display_name
  const noOutput = !result.stdout && !result.stderr

  let outcome: string
  if (result.timed_out) {
    outcome = 'Timed out'
  } else if (result.oom_killed) {
    outcome = 'Out of memory'
  } else if (result.exit_code === 0) {
    outcome = 'Exited with code 0'
  } else {
    outcome = `Exited with code ${result.exit_code}`
  }

  return (
    <div className="run-output-preview" data-testid="run-output-preview">
      <div className="run-output-meta" data-testid="run-output-meta">
        {(runnerName || result.ran_at) && (
          <span className="run-output-attribution" data-testid="run-output-attribution">
            {runnerName && (
              <span data-testid="run-output-runner">
                Run by {runnerName}
              </span>
            )}
            {result.ran_at && (
              <span
                className="run-output-time"
                data-testid="run-output-time"
                title={result.ran_at}
              >
                {runnerName ? ' at ' : 'Ran at '}
                {formatRanAt(result.ran_at)}
              </span>
            )}
          </span>
        )}
        <span data-testid="run-output-outcome">{outcome}</span>
        <span data-testid="run-output-stats">
          {result.compile_time_ms !== null && result.compile_time_ms !== undefined
            ? `${result.compile_time_ms.toFixed(0)} ms compile, `
            : ''}
          {result.execution_time_ms.toFixed(0)} ms, {result.peak_memory_mb.toFixed(1)} MB
        </span>
      </div>

      {result.saved_versions && result.saved_versions.length > 0 && (
        <div className="run-output-saved-versions" data-testid="run-output-saved-versions">
          <span>saved version: </span>
          {result.saved_versions.map((sv, idx) => (
            <span
              key={sv.module_id || `${sv.name}-${sv.version}`}
              data-testid={`saved-version-${sv.name}`}
              title={
                sv.saved_at
                  ? `Saved by ${sv.saved_by?.display_name ?? 'unknown'} at ${sv.saved_at}`
                  : undefined
              }
            >
              {idx > 0 ? ', ' : ''}
              {sv.name} v{sv.version}
            </span>
          ))}
        </div>
      )}

      {result.output_truncated && (
        <div className="run-output-notice" data-testid="run-output-truncated" role="status">
          Output was cut because it exceeded the size limit.
        </div>
      )}

      {result.message && (
        <div className="run-output-notice" data-testid="run-output-message">
          {result.message}
        </div>
      )}

      {analysisResult && (
        <div
          className={`run-output-notice ${
            analysisResult.status === 'fallback'
              ? 'analysis-fallback-notice'
              : 'analysis-success-notice'
          }`}
          data-testid="analysis-result-message"
          role="status"
        >
          <span className="analysis-result-label">
            {analysisResult.status === 'fallback' ? 'AI Analysis: ' : 'Analysis: '}
          </span>
          {analysisResult.message}
        </div>
      )}

      {originalInput !== null && minimizedInput !== null && (
        <section className="run-output-notice" data-testid="minimized-failing-input">
          <strong>Minimised failing input</strong>
          <div>Original: {originalInput.length} characters; minimised: {minimizedInput.length} characters</div>
          <pre className="output-text" data-testid="minimized-input-text">{minimizedInput}</pre>
          <div>Failure: {String(analysis?.failure_type ?? 'unknown')}</div>
          <div>Status: {String(analysis?.minimization_status ?? 'not_applicable')}</div>
          {canSave && onSaveTestCase && (
            <button type="button" onClick={saveTest} disabled={saveState === 'saving' || saveState === 'saved'}
                    data-testid="save-minimized-test-case">
              {saveState === 'saving' ? 'Saving…' : saveState === 'saved' ? 'Saved as test case' : 'Save as Test Case'}
            </button>
          )}
          {saveState === 'error' && <p role="alert">Could not save the test case. Try again.</p>}
        </section>
      )}

      {result.stdout && (
        <div className="output-section stdout-section">
          <div className="output-section-header">stdout:</div>
          <pre className="output-text stdout-text" data-testid="run-stdout">
            {result.stdout}
          </pre>
        </div>
      )}
      {result.stderr && (
        <div className="output-section stderr-section">
          <div className="output-section-header">stderr:</div>
          <pre className="output-text stderr-text" data-testid="run-stderr">
            {result.stderr}
          </pre>
        </div>
      )}
      {noOutput && <div className="output-empty">(No output generated)</div>}
    </div>
  )
}
