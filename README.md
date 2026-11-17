# Price Analyzer

Учебный стартовый репозиторий проекта «Разработка web-приложения анализа цен на товары».

## Запуск текущей версии

Нужны JDK 21, Maven и Node.js 22. Из корня проекта в двух терминалах:

```sh
mvn -f backend/pom.xml spring-boot:run
```

```sh
npm ci --prefix frontend
npm run dev --prefix frontend
```

Откройте http://localhost:5173 и создайте аккаунт. Доступ к каталогу и операциям с ценами получают зарегистрированные пользователи после входа. Данные сохраняются между запусками в локальной H2. Для PostgreSQL создайте отдельную БД, укажите `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` и запустите backend с `SPRING_PROFILES_ACTIVE=postgres`.

Для восстановления пароля задайте `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `APP_AUTH_FROM_ADDRESS` и `APP_AUTH_RESET_BASE_URL`. Последний параметр — адрес интерфейса по HTTPS. HTTP-адрес принимается для локального теста на loopback. При развёртывании за HTTPS-прокси задайте `SESSION_COOKIE_SECURE=true`. Почтовые секреты передавайте через окружение.

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

## Прототипирование (лабораторная работа №6)

В `frontend/` подготовлен интерактивный прототип: каталог товаров, ручной ввод цены, сравнение предложений по магазинам и график истории цен. Данные хранятся в браузере (`localStorage`), backend и PostgreSQL пока не подключены.

Инструкция по запуску: [`docs/lab6_run_guide.md`](docs/lab6_run_guide.md). Текст отчёта: [`docs/lab6_report.md`](docs/lab6_report.md).

Запуск прототипа:

```bash
cd frontend
npm install
npm run dev
```

## История и импорт

Используйте CSV или XLSX с заголовком `productId,store,price,date`. В XLSX данные находятся на одном листе. Дату можно указать как ячейку даты Excel или текст `YYYY-MM-DD`. Существующая запись для товара, магазина и даты пропускается. При ошибке строки изменения импорта откатываются.

`APP_PRICES_RETENTION_DAYS` по умолчанию равно `0` и хранит историю бессрочно. При положительном значении ежедневная очистка в 03:00 по часовому поясу сервера удаляет записи строго раньше даты «сегодня минус срок хранения». Перед включением очистки выберите срок для рабочей базы.

## Сравнение и отчёты

Укажите товар и период на странице сравнения. Для каждого магазина используется последнее наблюдение за период. На графике можно навести указатель на точку или выбрать её с клавиатуры.

В разделе «Отчёты» доступны фильтры периода, категории и магазина и группировка по категориям, магазинам или товарам. Для каждого товара в магазине рассчитывается изменение от первой цены до последней за период. Сводные суммы в рублях и процентах усредняются по сериям с наблюдениями минимум в две разные даты. PDF и XLSX используют те же фильтры и расчёт.
