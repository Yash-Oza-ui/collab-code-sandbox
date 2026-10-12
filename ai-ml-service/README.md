# AI/ML service

## Synthetic dataset (T-12)

The offline generator creates synthetic execution metrics for T-15 training.
It does not implement an HTTP service or a production anomaly threshold.

See [dataset documentation](docs/synthetic-dataset.md) for columns, generation
rules, assumptions, class ratio and regeneration commands.

```bash
python generate_dataset.py --seed 42 --rows 5000 --out runs.csv
python -m unittest discover -s tests -v
```

No third-party packages are required. Keep generated CSVs out of commits.
