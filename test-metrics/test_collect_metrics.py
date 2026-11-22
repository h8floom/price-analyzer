import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import collect_metrics


class CollectMetricsTest(unittest.TestCase):
    def test_counts_vitest_surefire_and_playwright_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = {
                "frontend/test-results/vitest-junit.xml":
                    '<testsuites><testsuite tests="16"/></testsuites>',
                "frontend/test-results/playwright-junit.xml":
                    '<testsuites><testsuite tests="3" failures="1"/></testsuites>',
                "backend/target/surefire-reports/TEST-example.xml":
                    '<testsuite tests="21" skipped="1"/>',
            }
            for name, contents in reports.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents, encoding="utf-8")
            output = root / "metrics"
            with patch.multiple(collect_metrics, ROOT=root, OUT=output):
                collect_metrics.main()
            metrics = json.loads((output / "metrics.json").read_text(encoding="utf-8"))
            self.assertEqual(
                [metrics[key] for key in ("test_total", "tests_passed", "tests_failed", "tests_skipped")],
                [40, 38, 1, 1],
            )
