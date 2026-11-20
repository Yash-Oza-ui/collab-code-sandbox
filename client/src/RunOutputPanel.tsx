import type { RunResponseData } from './runCode'
import type { AnalysisResultMessage } from './awarenessWs'

export interface RunOutputPanelProps {
  result: RunResponseData
  analysisResult?: AnalysisResultMessage | null
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
export default function RunOutputPanel({ result, analysisResult }: RunOutputPanelProps) {
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
