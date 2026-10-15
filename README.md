# Price Analyzer — starter for Lab 5

Учебный стартовый репозиторий проекта «Разработка web-приложения анализа цен на товары».

## Стек

- Frontend: React, Vite, ESLint, Vitest.
- Backend: Java 21, Spring Boot, Maven, JUnit/MockMvc, JaCoCo.
- CI: GitHub Actions.
- Правила коммитов: Conventional Commits + проверка pull request через commitlint.

## Структура

```text
.github/workflows/  # CI, метрики и проверка commit messages
backend/            # Spring Boot API и тесты
frontend/           # React/Vite, lint и unit-тесты
docs/lab5_report.md # текст отчёта для лабораторной работы
```

## Локальная проверка сообщений коммитов

В корне репозитория выполни `npm install`. Husky подключит локальный `commit-msg` hook, который проверяет сообщение коммита через commitlint. Проверка на GitHub дополнительно выполняется workflow `commit-check.yml`.

## Локальный запуск проверок

### Frontend

```bash
cd frontend
npm install
npm run lint
npm run test:coverage
npm run build
```

### Backend

```bash
cd backend
mvn verify
```

Для frontend следует добавить `frontend/package-lock.json` в Git после `npm install`. CI использует `npm ci`, чтобы ставить зависимости в соответствии с lock-файлом.

## Ветки

- `main` — стабильная версия.
- `develop` — интеграционная ветка.
- `feature/<краткое-название>` — новая функциональность.
- `fix/<краткое-название>` — исправление ошибки.
- `release/<версия>` — подготовка релиза.

Все изменения в `main` и `develop` проводятся через Pull Request после успешных проверок GitHub Actions.

## Формат коммитов

`type(scope): краткое описание`

Примеры:

- `feat(catalog): add product list`
- `fix(auth): handle invalid credentials`
- `test(prices): cover average price calculation`
- `docs(readme): describe local setup`
- `ci(github-actions): upload coverage report`

Типы: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `build`, `ci`, `perf`, `chore`, `revert`.

## GitHub settings, которые нужно включить вручную

1. Создать на GitHub пустой репозиторий, например `price-analyzer`.
2. Загрузить в него файлы этого набора.
3. В `Settings → General → Pull Requests` при желании отключить merge commit и оставить squash merge.
4. В `Settings → Branches` создать rulesets/правила для `main` и `develop`: Require a pull request before merging; Require status checks to pass; Block force pushes; Restrict deletions. Для `main` также требовать успешные CI-проверки.
5. После первого успешного запуска Actions выбрать в списке required status checks фактические имена проверок `quality-build-metrics` и `commitlint`, которые отображаются в GitHub. На GitHub Free доступность защиты веток зависит от видимости репозитория и возможностей тарифа; перед сдачей проверь это в настройках аккаунта.
6. Создать ветки `develop`, затем работать в `feature/...` и открывать Pull Request в `develop`; выпускать релиз через PR из `release/...` в `main`.

Точные действия и снимки экрана нужно сделать уже в собственном GitHub-репозитории. Сам этот архив не создаёт репозиторий и не меняет настройки аккаунта.
