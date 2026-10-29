# T-12 synthetic execution dataset

Traceability: FR8, FR6, NFR4, NFR8.2; T-12 / #20 / EPIC 4 / Sprint 1.
Source: `docs/anomaly-spec.md` (T-13) in the supplied repository snapshot.
Python 3 standard library only. No real runs, source code, output text, user IDs or user data.

## Regenerate and verify

From `ai-ml-service/`:

```bash
python generate_dataset.py --seed 42 --rows 5000 --anomaly-ratio 0.2 --out runs.csv
python generate_dataset.py --seed 42 --rows 5000 --anomaly-ratio 0.2 --out runs-again.csv
# Linux / WSL:
diff runs.csv runs-again.csv
# Windows PowerShell alternative:
(Get-FileHash runs.csv).Hash -eq (Get-FileHash runs-again.csv).Hash
python -m unittest discover -s tests -v
```

Equal parameters and Python runtime produce byte-identical UTF-8 CSVs with LF lines.
For comparisons across machines, use the same Python version. Boolean CSV values are
`True` and `False`; parse them explicitly if not using pandas type inference.
Do not commit generated datasets; regenerate on demand.

## Columns

| Column | Type / unit | Meaning |
| --- | --- | --- |
| execution_time_ms | integer, milliseconds | Synthetic wall time at run end |
| peak_memory_mb | number, MB as T-13 names it | Synthetic peak memory |
| stdout_bytes | nonnegative integer, bytes | Synthetic stdout size |
| stderr_bytes | nonnegative integer, bytes | Synthetic stderr size |
| exit_code | integer | Process exit code; normal is 0 or 1 |
| timed_out | boolean | Wall-time resource kill |
| oom_killed | boolean | Memory resource kill |
| language | string | cpp, python, c, java, javascript |
| label | integer | 0 normal; 1 anomalous |

The eight features match T-13 exactly. Process count is not available as a feature;
therefore the 64-process limit cannot be checked from this dataset. No feature is
added to infer it. T-06 exposes stdout/stderr text but does not expose byte counters.
The user confirms Jay approved availability, but the snapshot does not establish
whether counters mean captured or pre-truncation bytes. These synthetic counters
stay below a combined 1,048,576-byte cap and contain no text. Real integration must
resolve counter semantics with Jay; this generator does not modify the contracts.
Binary output units: 100 KB = 102,400 bytes; 900 KB = 921,600 bytes.

## How rows are produced

Normal rows: time 0–2499 ms, memory 1–59.99 MB, combined output 0–102399 bytes,
exit 0 or 1, no resource-kill flags. Each normal condition is required.
Anomalous rows cycle through six cases: time stress 4501–4999 ms; timeout at
5000 ms with exit 137; memory stress 115.01–127.99 MB; OOM at 128 MB with exit
137; combined output 921601–1048576 bytes; and crash exit 139 with time
4001–4500 ms and memory 100.01–114.99 MB. These map to T-13 Rules 1–4 and
EXCESSIVE_EXECUTION_TIME, HIGH_MEMORY_USAGE, OUTPUT_FLOODING,
ABNORMAL_TERMINATION respectively. Output is randomly split between streams.
Other measurements use normal ranges, allowing individual feature overlap.
These are bounded synthetic examples, not measured language-specific workloads
or a claim about resource-kill measurement accuracy.

### Resolution of T-13 ambiguity

T-13 section 3.2 says any buffer-range metric excludes a row, but section 3.4
and example S06 label a 4200 ms / 108 MB / exit 139 crash anomalous. Following
S06 and the explicit OR rules, an anomaly trigger takes precedence. Rows with
no trigger must meet every normal condition; otherwise they are excluded.
Boundary values alone do not trigger anomalies. This interpretation is recorded
here for Prince's review; the original T-13 document is unchanged.

T-12 asks for some overlap and T-13 asks for clean labels: overlap exists in
individual measurements, while complete rows satisfy unambiguous class rules.
This is not a production anomaly score threshold; that remains Sprint 3 work.

## Ratio, coverage and limitations

Default: 80% normal / 20% anomalous, a chosen training fixture ratio, not estimated
real prevalence. `--anomaly-ratio` controls it. Anomalous count is rounded with
`int(rows * ratio + 0.5)`; the remainder is normal. Each class must contain at
least five rows, ensuring all five languages in both classes. A balanced language list is independently shuffled within each class before
anomaly cases cycle through their six-case schedule. A local seeded generator
then shuffles the final rows. This removes the shared-index coupling; finite
samples may still have random correlations. The default-seed test checks all
30 language/case combinations and bounds sampling imbalance. Very small datasets may not
cover every anomaly case; default 5000 rows covers all six.

Synthetic clean labels may make baseline performance optimistic. Real validation,
language calibration, boundary runs and final warning thresholds belong to later
work. No model, endpoint, warning UI or external AI call is introduced here.

## Automated validation

Eight tests cover schema and integrity, per-row classification, all six cases,
combined-output boundaries, S06 precedence, reproducible CLI bytes, custom ratios,
changed seeds and invalid CLI arguments. A mocked classifier test confirms that
the generator rejects rows whose intended label is not established.
The revised assignment changes CSV bytes from the earlier generator version;
repeatability applies to this version with identical seed and parameters.
