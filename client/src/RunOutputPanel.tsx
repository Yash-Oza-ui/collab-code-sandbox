import type { RunResponseData } from './runCode'

export interface RunOutputPanelProps {
  result: RunResponseData
}

function formatRanAt(ranAt: string): string {
  const date = new Date(ranAt)
  return Number.isNaN(date.getTime()) ? ranAt : date.toLocaleTimeString()
}

/**
 * Output of one run (T-11, US-08). The same component shows the result for the person who
 * pressed Run and, through the `run-result` frame, for every other participant in the room.
 * Only `runner.display_name` is shown; ids are never rendered.
 */
export default function RunOutputPanel({ result }: RunOutputPanelProps) {
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
        {runnerName && (
          <span data-testid="run-output-runner">
            Run by {runnerName}
            {result.ran_at ? ` at ${formatRanAt(result.ran_at)}` : ''}
          </span>
        )}
        <span data-testid="run-output-outcome">{outcome}</span>
        <span data-testid="run-output-stats">
          {result.execution_time_ms.toFixed(0)} ms, {result.peak_memory_mb.toFixed(1)} MB
        </span>
      </div>

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
