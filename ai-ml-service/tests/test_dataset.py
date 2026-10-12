"""T-12 contract, boundary and reproducibility checks; standard library only."""
import csv
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from generate_dataset import COLUMNS, LANGUAGES, classify, generate


class DatasetTests(unittest.TestCase):
    def test_schema_labels_coverage_limits_and_rules(self):
        data = generate()
        self.assertEqual(Counter(r['label'] for r in data), {0: 4000, 1: 1000})
        for label in (0, 1):
            self.assertEqual({r['language'] for r in data if r['label'] == label},
                             set(LANGUAGES))
        for row in data:
            self.assertEqual(tuple(row), COLUMNS)
            self.assertEqual(classify(row), row['label'])
            self.assertGreaterEqual(row['execution_time_ms'], 0)
            self.assertLessEqual(row['execution_time_ms'], 5000)
            self.assertTrue(0 <= row['peak_memory_mb'] <= 128)
            self.assertTrue(0 <= row['stdout_bytes'] + row['stderr_bytes'] <= 1048576)
            for key in ('stdout_bytes', 'stderr_bytes', 'exit_code'):
                self.assertIsInstance(row[key], int)
            for key in ('timed_out', 'oom_killed'):
                self.assertIsInstance(row[key], bool)
            if row['label'] == 0:
                self.assertLess(row['execution_time_ms'], 2500)
                self.assertLess(row['peak_memory_mb'], 60)
                self.assertLess(row['stdout_bytes'] + row['stderr_bytes'], 102400)
                self.assertIn(row['exit_code'], (0, 1))
                self.assertFalse(row['timed_out'] or row['oom_killed'])

    def test_cli_byte_reproducibility_and_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / name for name in ('a.csv', 'b.csv')]
            for path in paths:
                subprocess.run([sys.executable, str(ROOT / 'generate_dataset.py'),
                                '--seed', '42', '--rows', '5000', '--out', str(path)],
                               check=True, capture_output=True)
            self.assertEqual(paths[0].read_bytes(), paths[1].read_bytes())
            with paths[0].open(newline='') as handle:
                reader = csv.DictReader(handle)
                self.assertEqual(reader.fieldnames, list(COLUMNS))
                self.assertEqual(len(list(reader)), 5000)
        self.assertNotEqual(generate(seed=42), generate(seed=43))

    def test_boundaries_and_s06_precedence(self):
        row = generate(rows=100)[0].copy()
        row.update(execution_time_ms=100, peak_memory_mb=10, stdout_bytes=0,
                   stderr_bytes=0, exit_code=0, timed_out=False, oom_killed=False)
        self.assertEqual(classify(row), 0)
        for field, values in (('execution_time_ms', (2500, 4500)),
                              ('peak_memory_mb', (60, 115)),
                              ('stdout_bytes', (102400, 921600)),
                              ('exit_code', (2, 127))):
            for value in values:
                changed = dict(row, **{field: value})
                self.assertIsNone(classify(changed))
        self.assertEqual(classify(dict(row, execution_time_ms=4200,
                                       peak_memory_mb=108, exit_code=139)), 1)
        for change in ({'execution_time_ms': 4501}, {'peak_memory_mb': 115.01},
                       {'stdout_bytes': 921601}, {'timed_out': True},
                       {'oom_killed': True}):
            self.assertEqual(classify(dict(row, **change)), 1)

    def test_parameters(self):
        self.assertEqual(Counter(r['label'] for r in generate(rows=100, anomaly_ratio=.3)),
                         {0: 70, 1: 30})
        for rows, ratio in ((9, .2), (100, 0), (100, 1), (10, .2)):
            with self.assertRaises(ValueError):
                generate(rows=rows, anomaly_ratio=ratio)


if __name__ == '__main__':
    unittest.main()
