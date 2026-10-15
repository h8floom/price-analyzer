#!/usr/bin/env python3
"""Collect CI metrics from ESLint, Vitest, JaCoCo and Git history."""
from __future__ import annotations

import json
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "test-metrics"


def read_json(path: Path, fallback):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return fallback


def git_output(*args: str) -> str:
    try:
        return subprocess.check_output(["git", *args], cwd=ROOT, text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def count_changed_lines_since_week() -> tuple[int, float]:
    raw = git_output("log", "--since=7 days ago", "--numstat", "--format=@@%H")
    commits = 0
    additions = 0
    deletions = 0
    current_commit_has_changes = False
    for line in raw.splitlines():
        if line.startswith("@@"):
            if current_commit_has_changes:
                current_commit_has_changes = False
            commits += 1
        elif line.strip():
            parts = line.split("\t")
            if len(parts) >= 3 and parts[0].isdigit() and parts[1].isdigit():
                additions += int(parts[0])
                deletions += int(parts[1])
                current_commit_has_changes = True
    total = additions + deletions
    return commits, (total / commits if commits else 0.0)


def parse_test_xml(path: Path) -> tuple[int, int, int, int]:
    """Return tests, passed, failed, skipped for JUnit/Surefire XML."""
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return (0, 0, 0, 0)
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    totals = [0, 0, 0, 0]
    for suite in suites:
        tests = int(suite.attrib.get("tests", 0))
        failures = int(suite.attrib.get("failures", 0))
        errors = int(suite.attrib.get("errors", 0))
        skipped = int(suite.attrib.get("skipped", 0))
        totals[0] += tests
        totals[2] += failures + errors
        totals[3] += skipped
    totals[1] = max(totals[0] - totals[2] - totals[3], 0)
    return tuple(totals)


def coverage_pct() -> float | None:
    front = read_json(ROOT / "frontend/coverage/coverage-summary.json", {})
    front_lines = front.get("total", {}).get("lines", {}) if isinstance(front, dict) else {}
    front_covered = int(front_lines.get("covered", 0))
    front_total = int(front_lines.get("total", 0))
    back_path = ROOT / "backend/target/site/jacoco/jacoco.xml"
    back_covered = back_total = 0
    if back_path.exists():
        try:
            root = ET.parse(back_path).getroot()
            counter = root.find("counter[@type='LINE']")
            if counter is not None:
                back_covered = int(counter.attrib.get("covered", 0))
                back_total = back_covered + int(counter.attrib.get("missed", 0))
        except ET.ParseError:
            pass
    covered = front_covered + back_covered
    total = front_total + back_total
    return (100.0 * covered / total) if total else None


def source_lines() -> int:
    count = 0
    for folder in (ROOT / "frontend/src", ROOT / "backend/src"):
        if folder.exists():
            for path in folder.rglob("*"):
                if path.suffix in {".js", ".jsx", ".java"}:
                    try:
                        count += sum(1 for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
                    except OSError:
                        pass
    return count


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    eslint_path = OUT / "eslint-report.json"
    eslint = read_json(eslint_path, None)
    eslint_available = eslint_path.exists() and isinstance(eslint, list)
    lint_errors = sum(int(item.get("errorCount", 0)) for item in eslint if isinstance(item, dict)) if eslint_available else None
    lint_warnings = sum(int(item.get("warningCount", 0)) for item in eslint if isinstance(item, dict)) if eslint_available else None

    test_files = [ROOT / "frontend/coverage/junit.xml"]
    test_files += list((ROOT / "backend/target/surefire-reports").glob("TEST-*.xml")) if (ROOT / "backend/target/surefire-reports").exists() else []
    existing_test_files = [path for path in test_files if path.exists()]
    test_totals = [0, 0, 0, 0]
    for path in existing_test_files:
        result = parse_test_xml(path)
        test_totals = [a + b for a, b in zip(test_totals, result)]
    if not existing_test_files:
        test_totals = [None, None, None, None]

    commit_count, avg_commit_size = count_changed_lines_since_week()
    start_file = OUT / "start_epoch.txt"
    try:
        elapsed_seconds = max(0, int(time.time()) - int(start_file.read_text().strip()))
    except (OSError, ValueError):
        elapsed_seconds = 0

    metrics = {
        "lint_errors": lint_errors,
        "lint_warnings": lint_warnings,
        "test_total": test_totals[0],
        "tests_passed": test_totals[1],
        "tests_failed": test_totals[2],
        "tests_skipped": test_totals[3],
        "line_coverage_percent": coverage_pct(),
        "commits_last_7_days": commit_count,
        "average_commit_size_lines_last_7_days": round(avg_commit_size, 2),
        "pipeline_duration_seconds": elapsed_seconds,
        "nonempty_source_lines": source_lines(),
        "commit_sha": git_output("rev-parse", "--short", "HEAD") or "unknown",
    }
    (OUT / "metrics.json").write_text(json.dumps(metrics, indent=2, ensure_ascii=False), encoding="utf-8")

    coverage = "н/д" if metrics["line_coverage_percent"] is None else f'{metrics["line_coverage_percent"]:.2f}%'
    rows = [
        ("Ошибки ESLint", "н/д" if metrics["lint_errors"] is None else metrics["lint_errors"]),
        ("Предупреждения ESLint", "н/д" if metrics["lint_warnings"] is None else metrics["lint_warnings"]),
        ("Покрытие строк кода тестами (frontend + backend)", coverage),
        ("Тесты: пройдено", "н/д" if metrics["tests_passed"] is None else metrics["tests_passed"]),
        ("Тесты: упало", "н/д" if metrics["tests_failed"] is None else metrics["tests_failed"]),
        ("Коммиты за последние 7 дней", metrics["commits_last_7_days"]),
        ("Средний размер коммита, строк добавлено + удалено", metrics["average_commit_size_lines_last_7_days"]),
        ("Время выполнения CI, секунд", metrics["pipeline_duration_seconds"]),
        ("Непустые строки исходного кода (дополнительная метрика)", metrics["nonempty_source_lines"]),
    ]
    report = [
        "# Отчёт о метриках качества",
        "",
        f'- Коммит: `{metrics["commit_sha"]}`',
        "- Метрики автоматически собраны GitHub Actions. Значения могут быть `0` или `н/д`, если workflow не нашёл соответствующий отчёт (например, до первого тестового запуска).",
        "",
        "| Метрика | Значение |",
        "|---|---:|",
    ]
    report.extend(f"| {name} | {value} |" for name, value in rows)
    report.extend(["", "Покрытие считается по отчётам Vitest и JaCoCo, если они сформированы. Коммиты и размер изменений рассчитываются по доступной истории Git за последние семь дней."])
    (OUT / "metrics-report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print("\n".join(report))


if __name__ == "__main__":
    main()
