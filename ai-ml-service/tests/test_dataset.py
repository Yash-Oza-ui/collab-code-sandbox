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


class ExtendedDatasetTests(unittest.TestCase):
    def base(self, **changes):
        row = dict(execution_time_ms=100, peak_memory_mb=10, stdout_bytes=0,
                   stderr_bytes=0, exit_code=0, timed_out=False, oom_killed=False,
                   language='python')
        row.update(changes)
        return row

    def case(self, row):
        if row['timed_out']:
            return 'timeout'
        if row['oom_killed']:
            return 'oom'
        if row['execution_time_ms'] > 4500:
            return 'time'
        if row['peak_memory_mb'] > 115:
            return 'memory'
        if row['stdout_bytes'] + row['stderr_bytes'] > 921600:
            return 'output'
        if row['exit_code'] >= 128:
            return 'crash'
        return None

    def test_six_cases_and_language_assignment(self):
        examples = (
            {'execution_time_ms': 4501},
            {'execution_time_ms': 5000, 'timed_out': True, 'exit_code': 137},
            {'peak_memory_mb': 115.01},
            {'peak_memory_mb': 128, 'oom_killed': True, 'exit_code': 137},
            {'stdout_bytes': 500000, 'stderr_bytes': 421601},
            {'execution_time_ms': 4200, 'peak_memory_mb': 108, 'exit_code': 139},
        )
        for example in examples:
            with self.subTest(example=example):
                self.assertEqual(classify(self.base(**example)), 1)
        data = generate()
        pairs = Counter((r['language'], self.case(r)) for r in data if r['label'] == 1)
        cases = {'time', 'timeout', 'memory', 'oom', 'output', 'crash'}
        self.assertEqual({case for _, case in pairs}, cases)
        for language in LANGUAGES:
            for case in cases:
                self.assertGreater(pairs[language, case], 0)
        # Independent random assignment permits sampling variation, not perfect balance.
        for case in cases:
            total = sum(pairs[language, case] for language in LANGUAGES)
            for language in LANGUAGES:
                self.assertLess(abs(pairs[language, case] / total - .2), .12)
        for row in data:
            self.assertIn(classify(row), (0, 1))
            self.assertEqual(classify(row), row['label'])
            self.assertIn(row['language'], LANGUAGES)
            self.assertGreaterEqual(row['stdout_bytes'], 0)
            self.assertGreaterEqual(row['stderr_bytes'], 0)
            self.assertTrue(1 <= row['peak_memory_mb'] <= 128)

    def test_combined_output_boundaries(self):
        for total, expected in ((102399, 0), (102400, None), (921600, None),
                                (921601, 1), (1048576, 1)):
            with self.subTest(total=total):
                row = self.base(stdout_bytes=total // 2,
                                stderr_bytes=total - total // 2)
                self.assertEqual(classify(row), expected)
        # Both individual streams below the normal boundary, combined exactly on it.
        self.assertIsNone(classify(self.base(stdout_bytes=51200, stderr_bytes=51200)))
        # A valid trigger overrides otherwise ambiguous output.
        self.assertEqual(classify(self.base(stdout_bytes=51200, stderr_bytes=51200,
                                            timed_out=True)), 1)

    def test_generator_checks_classifier(self):
        from unittest.mock import patch
        with patch('generate_dataset.classify', return_value=None):
            with self.assertRaisesRegex(ValueError, 'T-13 label'):
                generate()

    def test_cli_options_bytes_and_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            def run(name, *args):
                path = Path(directory) / name
                process = subprocess.run(
                    [sys.executable, str(ROOT / 'generate_dataset.py'),
                     '--out', str(path), *args], capture_output=True, text=True)
                return process, path
            args = ('--seed', '42', '--rows', '101', '--anomaly-ratio', '.3')
            first, a = run('a.csv', *args)
            second, b = run('b.csv', *args)
            third, c = run('c.csv', '--seed', '43', '--rows', '101',
                           '--anomaly-ratio', '.3')
            for process in (first, second, third):
                self.assertEqual(process.returncode, 0, process.stderr)
            self.assertEqual(a.read_bytes(), b.read_bytes())
            self.assertNotEqual(a.read_bytes(), c.read_bytes())
            self.assertNotIn(b'\r', a.read_bytes())
            with a.open(encoding='utf-8', newline='') as handle:
                reader = csv.DictReader(handle)
                self.assertEqual(reader.fieldnames, list(COLUMNS))
                records = list(reader)
            self.assertEqual(len(records), 101)
            self.assertEqual(Counter(r['label'] for r in records), {'0': 71, '1': 30})
            invalid = (
                (('--rows', '-1'), 'rows must'),
                (('--rows', '9'), 'rows must'),
                (('--rows', 'abc'), 'invalid int'),
                (('--anomaly-ratio', '0'), 'strictly between'),
                (('--anomaly-ratio', '1'), 'strictly between'),
                (('--anomaly-ratio', '-.1'), 'strictly between'),
                (('--anomaly-ratio', '1.1'), 'strictly between'),
                (('--anomaly-ratio', 'nan'), 'strictly between'),
                (('--anomaly-ratio', 'inf'), 'strictly between'),
                (('--anomaly-ratio', 'abc'), 'invalid float'),
                (('--rows', '10', '--anomaly-ratio', '.2'), 'at least five'),
                (('--rows', '100', '--anomaly-ratio', '.99'), 'at least five'),
            )
            for index, (options, message) in enumerate(invalid):
                with self.subTest(options=options):
                    process, path = run(f'bad-{index}.csv', *options)
                    self.assertNotEqual(process.returncode, 0)
                    self.assertIn('error:', process.stderr)
                    self.assertIn(message, process.stderr)
                    self.assertFalse(path.exists())


if __name__ == '__main__':
    unittest.main()
