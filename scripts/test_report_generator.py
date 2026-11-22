"""Unit tests for the offline report generator (Python standard library)."""
import json
import tempfile
import unittest
from pathlib import Path
from generate_test_report import report


class ReportGeneratorTest(unittest.TestCase):
    def test_parses_junit_coverage_and_produces_html_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'source/frontend').mkdir(parents=True)
            (root / 'source/backend').mkdir(parents=True)
            (root / 'source/e2e').mkdir(parents=True)
            (root / 'source/frontend/vitest-junit.xml').write_text(
                '<testsuites><testsuite name="frontend" tests="4" failures="1" errors="0" skipped="1"/></testsuites>')
            (root / 'source/backend/TEST-example.xml').write_text(
                '<testsuite name="backend" tests="3" failures="0" errors="0" skipped="0"/>')
            (root / 'source/e2e/playwright-junit.xml').write_text(
                '<testsuites><testsuite name="e2e" tests="2" failures="0" errors="0" skipped="0"/></testsuites>')
            (root / 'source/frontend/coverage-summary.json').write_text(
                json.dumps({'total': {'lines': {'pct': 75.5}}}))
            (root / 'source/backend/jacoco.xml').write_text(
                '<report><counter type="LINE" missed="20" covered="80"/></report>')
            data = report(root / 'source', root / 'output',
                          {'frontend': 'failure', 'backend': 'success', 'e2e': 'success'})
            self.assertEqual(data['tests']['Frontend (Vitest)']['passed'], 2)
            self.assertEqual(data['tests']['Backend (JUnit)']['passed'], 3)
            self.assertEqual(data['tests']['E2E (Playwright)']['passed'], 2)
            self.assertEqual(data['coverage']['frontend_lines_pct'], 75.5)
            self.assertEqual(data['coverage']['backend_lines_pct'], 80)
            self.assertIn('failure', (root / 'output/index.html').read_text())

    def test_missing_reports_are_not_false_successes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = report(root / 'missing', root / 'out',
                          {'frontend': 'failure', 'backend': 'skipped', 'e2e': 'skipped'})
            self.assertIsNone(data['tests']['Frontend (Vitest)'])
            self.assertIsNone(data['coverage']['frontend_lines_pct'])
            self.assertIn('нет данных', (root / 'out/index.html').read_text())


if __name__ == '__main__':
    unittest.main()
