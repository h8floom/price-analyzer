#!/usr/bin/env python3
"""Create a consolidated test report from JUnit XML and coverage files.

No third-party Python dependencies. Missing reports are explicitly marked as
'not available', never as zero failures or 100% coverage.
"""
from __future__ import annotations

import argparse
import html
import json
import os
from pathlib import Path
from datetime import datetime, timezone
import xml.etree.ElementTree as ET


def parse_junit(file: Path) -> dict | None:
    try:
        root = ET.parse(file).getroot()
    except (OSError, ET.ParseError):
        return None
    # JUnit root can be <testsuites> or <testsuite>.
    if root.tag == 'testsuite':
        suites = [root]
    elif root.tag == 'testsuites':
        suites = [child for child in root if child.tag == 'testsuite']
    else:
        return None
    total = failed = errors = skipped = 0
    for suite in suites:
        total += int(suite.get('tests', '0'))
        failed += int(suite.get('failures', '0'))
        errors += int(suite.get('errors', '0'))
        skipped += int(suite.get('skipped', '0'))
    return {'tests': total, 'passed': max(0, total - failed - errors - skipped),
            'failures': failed, 'errors': errors, 'skipped': skipped}


def combine_junit(files: list[Path]) -> dict | None:
    summaries = [parse_junit(f) for f in files]
    summaries = [s for s in summaries if s is not None]
    if not summaries:
        return None
    return {key: sum(s[key] for s in summaries) for key in summaries[0]}


def frontend_coverage(paths: list[Path]) -> float | None:
    for path in paths:
        try:
            data = json.loads(path.read_text(encoding='utf-8'))
            return float(data['total']['lines']['pct'])
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
            continue
    return None


def backend_coverage(paths: list[Path]) -> float | None:
    for path in paths:
        try:
            root = ET.parse(path).getroot()
            for counter in root.findall('counter'):
                if counter.get('type') == 'LINE':
                    covered = int(counter.get('covered', '0'))
                    missed = int(counter.get('missed', '0'))
                    return round(100 * covered / (covered + missed), 2) if covered + missed else None
        except (OSError, ET.ParseError, ValueError):
            continue
    return None


def display(value):
    return 'нет данных' if value is None else str(value)


def report(input_dir: Path, output_dir: Path, states: dict[str, str]):
    output_dir.mkdir(parents=True, exist_ok=True)
    fe = input_dir / 'frontend'
    be = input_dir / 'backend'
    e2e = input_dir / 'e2e'
    groups = {
        'Frontend (Vitest)': combine_junit(list(fe.rglob('vitest-junit.xml'))),
        'Backend (JUnit)': combine_junit([p for p in be.rglob('TEST-*.xml')]),
        'E2E (Playwright)': combine_junit(list(e2e.rglob('playwright-junit.xml'))),
    }
    coverages = {
        'frontend_lines_pct': frontend_coverage(list(fe.rglob('coverage-summary.json'))),
        'backend_lines_pct': backend_coverage(list(be.rglob('jacoco.xml'))),
    }
    data = {
        'generated_at_utc': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'commit': os.environ.get('GITHUB_SHA', 'local'),
        'run_id': os.environ.get('GITHUB_RUN_ID', 'local'),
        'jobs': states,
        'tests': groups,
        'coverage': coverages,
    }
    (output_dir / 'summary.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    th = '<tr><th>Набор тестов</th><th>Всего</th><th>Пройдено</th><th>Провалено</th><th>Ошибки</th><th>Пропущено</th></tr>'
    trs = []
    for name, values in groups.items():
        cols = [name] + [display(values.get(k) if values else None)
                         for k in ('tests', 'passed', 'failures', 'errors', 'skipped')]
        trs.append('<tr>' + ''.join('<td>' + html.escape(str(x)) + '</td>' for x in cols) + '</tr>')
    states_html = ''.join(f'<li><strong>{html.escape(k)}</strong>: {html.escape(v)}</li>' for k,v in states.items())
    cov_html = ''.join(f'<li>{html.escape(k)}: {html.escape(display(v))}%</li>' if v is not None
                       else f'<li>{html.escape(k)}: нет данных</li>' for k,v in coverages.items())
    template = f'''<!doctype html><html lang="ru"><head><meta charset="utf-8"/>
<title>ЛР7 — результаты тестирования PricePilot</title>
<style>body{{font:16px system-ui,Arial,sans-serif;max-width:980px;margin:48px auto;padding:0 20px;color:#1f2937}}
h1{{font-size:28px}}table{{border-collapse:collapse;width:100%;margin:24px 0}}th,td{{border:1px solid #d1d5db;padding:11px;text-align:left}}
th{{background:#f0f2fc}}.note{{background:#f3f4f6;padding:14px;border-radius:8px}}code{{color:#4044bb}}</style>
</head><body><h1>Автоматический отчёт о тестировании — ЛР №7</h1>
<p>Проект PricePilot • {html.escape(data['generated_at_utc'])} • коммит <code>{html.escape(data['commit'])}</code></p>
<h2>Состояние задач GitHub Actions</h2><ul>{states_html}</ul>
<h2>Результаты тестов</h2><table><thead>{th}</thead><tbody>{''.join(trs)}</tbody></table>
<h2>Покрытие тестами</h2><ul>{cov_html}</ul>
<p class="note">«Нет данных» означает, что XML/JSON-отчёт отсутствует или не прочитан; это не означает успешный тест.
Playwright HTML-отчёт, screenshots и traces сохранены отдельным артефактом.</p>
</body></html>'''
    (output_dir / 'index.html').write_text(template, encoding='utf-8')
    md = ['## ЛР №7 — автоматический отчёт тестирования', '',
          f"Коммит: `{data['commit']}`", '', '| Job | Состояние |', '|---|---|']
    md += [f'| {k} | {v} |' for k,v in states.items()]
    md += ['', '| Набор | Пройдено | Упало | Всего |', '|---|---:|---:|---:|']
    for k,v in groups.items():
        md.append(f"| {k} | {display(v['passed'] if v else None)} | "
                  f"{display((v['failures'] + v['errors']) if v else None)} | {display(v['tests'] if v else None)} |")
    md += ['', f"Покрытие frontend: {str(coverages['frontend_lines_pct']) + '%' if coverages['frontend_lines_pct'] is not None else 'нет данных'}",
           f"Покрытие backend: {str(coverages['backend_lines_pct']) + '%' if coverages['backend_lines_pct'] is not None else 'нет данных'}", '',
           'Отсутствующие отчёты отображаются как «нет данных», а не как пройденные тесты.']
    summary = '\n'.join(md) + '\n'
    (output_dir / 'summary.md').write_text(summary, encoding='utf-8')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8').write(summary)
    print(summary)
    return data


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=Path, default=Path('ci-artifacts'))
    parser.add_argument('--output', type=Path, default=Path('lab7-test-report'))
    args = parser.parse_args()
    states = {k: os.environ.get(k.upper() + '_STATUS', 'не выполнялось')
              for k in ('frontend', 'backend', 'e2e')}
    report(args.input, args.output, states)


if __name__ == '__main__':
    main()
