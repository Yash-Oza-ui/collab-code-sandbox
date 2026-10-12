"""T-12 synthetic metrics only; T-13 rules, anomaly rules take precedence."""
import argparse
import csv
import random
from pathlib import Path

LANGUAGES = ('cpp', 'python', 'c', 'java', 'javascript')
COLUMNS = ('execution_time_ms', 'peak_memory_mb', 'stdout_bytes', 'stderr_bytes',
           'exit_code', 'timed_out', 'oom_killed', 'language', 'label')
OUTPUT_CAP = 1048576


def classify(row):
    """Return 0, 1, or None (excluded); not a production score threshold."""
    time, memory = row['execution_time_ms'], row['peak_memory_mb']
    output = row['stdout_bytes'] + row['stderr_bytes']
    if (time > 4500 or row['timed_out'] or memory > 115 or row['oom_killed']
            or output > 921600
            or (row['exit_code'] >= 128 and (memory > 100 or time > 4000))):
        return 1
    if (time < 2500 and memory < 60 and output < 102400
            and not row['timed_out'] and not row['oom_killed']
            and row['exit_code'] in (0, 1)):
        return 0
    return None


def generate(seed=42, rows=5000, anomaly_ratio=0.2):
    """Guarantee both labels and every language in each class."""
    if rows < 10 or not 0 < anomaly_ratio < 1:
        raise ValueError('rows must be >= 10 and anomaly-ratio strictly between 0 and 1')
    anomalous = int(rows * anomaly_ratio + 0.5)
    if min(anomalous, rows - anomalous) < 5:
        raise ValueError('each class needs at least five rows for language coverage')
    rng = random.Random(seed)
    result = []
    for label, count in ((0, rows - anomalous), (1, anomalous)):
        for index in range(count):
            total = rng.randint(0, 102399)
            row = dict(execution_time_ms=rng.randint(0, 2499),
                       peak_memory_mb=round(rng.uniform(1, 59.99), 2),
                       stdout_bytes=total, stderr_bytes=0,
                       exit_code=rng.choice((0, 0, 0, 1)),
                       timed_out=False, oom_killed=False,
                       language=LANGUAGES[index % 5], label=label)
            if label:
                # Cycle through every rule, including explicit resource kills.
                kind = index % 6
                if kind == 0:
                    row['execution_time_ms'] = rng.randint(4501, 4999)
                elif kind == 1:
                    row['execution_time_ms'] = 5000
                    row['timed_out'], row['exit_code'] = True, 137
                elif kind == 2:
                    row['peak_memory_mb'] = round(rng.uniform(115.01, 127.99), 2)
                elif kind == 3:
                    row['peak_memory_mb'] = 128.0
                    row['oom_killed'], row['exit_code'] = True, 137
                elif kind == 4:
                    total = rng.randint(921601, OUTPUT_CAP)
                else:
                    row['exit_code'] = 139
                    row['peak_memory_mb'] = round(rng.uniform(100.01, 114.99), 2)
                    row['execution_time_ms'] = rng.randint(4001, 4500)
            row['stderr_bytes'] = rng.randint(0, total)
            row['stdout_bytes'] = total - row['stderr_bytes']
            result.append(row)
    rng.shuffle(result)
    return result


def write_csv(path, data):
    with Path(path).open('w', encoding='utf-8', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=COLUMNS, lineterminator='\n')
        writer.writeheader()
        writer.writerows(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--rows', type=int, default=5000)
    parser.add_argument('--anomaly-ratio', type=float, default=0.2)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    try:
        data = generate(args.seed, args.rows, args.anomaly_ratio)
    except ValueError as error:
        parser.error(str(error))
    write_csv(args.out, data)
    print(f'Wrote {len(data)} rows to {args.out}')


if __name__ == '__main__':
    main()
