import { test, expect } from '@playwright/test';
import { join } from 'node:path';

function uniqueName(prefix) {
  return `${prefix} ${Date.now()}-${Math.round(Math.random() * 1_000_000)}`;
}

const password = 'playwright-price-analyzer';
const origin = 'http://127.0.0.1:5173';
const browserAuthHeaders = { Origin: origin, 'X-Price-Auth': '1' };
const credentials = new WeakMap();

async function capture(page, name) {
  if (process.env.LAB8_SCREENSHOT_DIR) {
    await page.screenshot({ path: join(process.env.LAB8_SCREENSHOT_DIR, name + '.png'), fullPage: true });
  }
}

async function signInForm(page, email) {
  await page.getByLabel('Электронная почта').fill(email);
  await page.getByLabel('Пароль').fill(password);
  const received = page.waitForResponse((response) => response.url().endsWith('/api/auth/login'));
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  const response = await received;
  expect(response.status()).toBe(200);
  await rememberToken(page.request, response);
  await expect(page.getByRole('heading', { name: 'Обзор цен' })).toBeVisible();
}

async function post(request, path, options) {
  return request.post(path, { ...options, headers: bearer(request) });
}

function bearer(request) {
  const token = credentials.get(request);
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function rememberToken(request, response) {
  const { accessToken, tokenType } = await response.json();
  expect(tokenType === 'Bearer' && typeof accessToken === 'string' && accessToken.split('.').length === 3).toBe(true);
  credentials.set(request, accessToken);
}

async function signInApi(request, email) {
  const response = await request.post('/api/auth/login', { headers: browserAuthHeaders, data: { email, password } });
  expect(response.status()).toBe(200);
  await rememberToken(request, response);
}

async function signIn(page) {
  const email = 'playwright-admin@example.test';
  await signInApi(page.request, email);
}

async function createUser(page) {
  const email = `test-${Date.now()}-${Math.random().toString(36).slice(2)}@example.test`;
  expect((await post(page.request, '/api/auth/register', { data: { email, password } })).status()).toBe(201);
  await signInApi(page.request, email);
}

test('пользователь регистрируется, сохраняет сеанс и выходит', async ({ page }) => {
  expect((await page.request.get('/api/products')).status()).toBe(401);
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать аккаунт' }).click();
  await page.getByLabel('Электронная почта').fill(`browser-signup-${Date.now()}@example.test`);
  await page.getByLabel('Пароль').fill(password);
  await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
  await expect(page.getByRole('heading', { name: 'Обзор цен' })).toBeVisible();
  const refreshed = page.waitForResponse((response) => response.url().endsWith('/api/auth/refresh'));
  await page.reload();
  expect((await refreshed).status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Обзор цен' })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('.logo b')).toBeHidden();
  await page.getByRole('button', { name: 'Выйти' }).click();
  await expect(page.getByRole('heading', { name: 'Вход в Price Analyzer' })).toBeVisible();
  await page.goto('/#reset=' + 'x'.repeat(43));
  await expect(page.getByRole('heading', { name: 'Новый пароль' })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  expect((await page.request.get('/api/products')).status()).toBe(401);
});

test('избранное переживает повторный вход и скрыто от другого аккаунта', async ({ page, browser }) => {
  const admin = await browser.newPage();
  const name = uniqueName('Кофе для избранного');
  try {
    await signIn(admin);
    const created = await post(admin.request, '/api/products', {
      data: { name, category: 'Напитки', unit: 'шт.' },
    });
    expect(created.status()).toBe(201);
  } finally {
    await admin.close();
  }

  await createUser(page);
  await page.goto('/');
  await page.getByRole('button', { name: 'Каталог товаров' }).click();
  const heart = page.getByRole('button', { name: `Избранное: ${name}` });
  await heart.click();
  await expect(heart).toHaveAttribute('aria-pressed', 'true');
  await page.reload();
  await page.getByRole('navigation').getByRole('button', { name: 'Избранное', exact: true }).click();
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.getByRole('button', { name: 'Выйти' }).click();
  await expect(page.getByRole('heading', { name: 'Вход в Price Analyzer' })).toBeVisible();
  await createUser(page);
  await page.reload();
  await page.getByRole('navigation').getByRole('button', { name: 'Избранное', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Избранное пока пусто' })).toBeVisible();
  await expect(page.getByRole('heading', { name, exact: true })).toHaveCount(0);
});

test('избранный товар получает уведомление о цене, которую изменил администратор', async ({ page, browser }, testInfo) => {
  const product = testInfo.retry ? uniqueName('Чай ассам, 100 г') : 'Чай ассам, 100 г';
  const email = `watch-${Date.now()}@example.test`;
  const admin = await browser.newPage();
  // Keep local evidence independent of the availability of the external font CDN.
  for (const tab of [page, admin]) {
    await tab.route('https://fonts.googleapis.com/**', (route) => route.abort());
  }
  try {
    await admin.goto('/');
    await signInForm(admin, 'playwright-admin@example.test');
    const created = await post(admin.request, '/api/products', {
      data: { name: product, category: 'Напитки', unit: 'уп.', description: 'Листовой чёрный чай' },
    });
    expect(created.status()).toBe(201);
    const { id: productId } = await created.json();
    const price = await post(admin.request, '/api/prices', {
      data: { productId, store: 'Чайный магазин', price: 320, date: '2026-11-17' },
    });
    expect(price.status()).toBe(201);
    await admin.reload();

    await page.clock.install();
    await page.goto('/');
    await page.getByRole('button', { name: 'Создать аккаунт' }).click();
    await page.getByLabel('Электронная почта').fill(email);
    await page.getByLabel('Пароль').fill(password);
    await page.getByRole('button', { name: 'Зарегистрироваться' }).click();
    await page.getByRole('button', { name: 'Выйти' }).click();
    const startingPoll = page.waitForResponse((response) => response.url().endsWith('/api/notifications/unread'));
    await signInForm(page, email);
    expect(await (await startingPoll).json()).toEqual({ count: 0 });
    expect(await (await page.request.get('/api/auth/me', { headers: bearer(page.request) })).json()).toMatchObject({ role: 'USER' });
    await expect(page.getByRole('button', { name: /Добавить цену|Управление/ })).toHaveCount(0);
    await page.getByRole('button', { name: 'Каталог товаров' }).click();
    await page.getByRole('button', { name: `Избранное: ${product}` }).click();
    await page.getByRole('button', { name: 'Избранное', exact: true }).click();
    await expect(page.getByRole('heading', { name: product })).toBeVisible();
    await capture(page, '01-user-favorites');

    expect(await (await admin.request.get('/api/auth/me', { headers: bearer(admin.request) })).json()).toMatchObject({ role: 'ADMIN' });
    await admin.getByRole('button', { name: 'Управление' }).click();
    await expect(admin.getByLabel('Дней хранения')).toHaveValue('0');
    await capture(admin, '02-admin-settings');
    await admin.getByRole('button', { name: 'Сравнение цен' }).click();
    await admin.getByLabel('Товар для сравнения').selectOption({ label: product });
    await admin.getByRole('button', { name: 'Изменить цену' }).click();
    const form = admin.getByRole('dialog');
    await form.getByLabel('Новая цена, ₽').fill('279.90');
    await form.getByRole('button', { name: 'Сохранить цену' }).click();
    await expect(admin.getByRole('status').filter({ hasText: 'предыдущая запись осталась' })).toBeVisible();

    const poll = page.waitForResponse((response) => response.url().endsWith('/api/notifications/unread'));
    await page.clock.fastForward(45_000);
    expect(await (await poll).json()).toEqual({ count: 1 });
    await page.getByRole('button', { name: 'Уведомления, непрочитанных: 1' }).click();
    const item = page.locator('.inbox-item');
    await expect(item).toContainText('Чайный магазин');
    await expect(item).toContainText(/320,00\s*₽.*279,90\s*₽/s);
    await expect(item.getByText('Цена снизилась')).toBeVisible();
    await capture(page, '03-user-unread');
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await capture(page, '04-user-mobile');
    await item.getByRole('button', { name: 'Отметить прочитанным' }).click();
    await expect(page.getByRole('button', { name: 'Уведомления', exact: true })).toBeVisible();
    await expect(item.getByText('Непрочитано')).toHaveCount(0);
    await item.getByRole('link', { name: product }).click();
    await expect(page.getByLabel('Товар для сравнения')).toHaveValue(String(productId));
    await expect(page.getByRole('button', { name: 'Изменить цену' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Уведомления', exact: true }).click();
    await page.getByLabel('Получать уведомления').selectOption('DECREASE_ONLY');
    await expect(page.getByLabel('Получать уведомления')).toHaveValue('DECREASE_ONLY');
    await capture(page, '05-user-read-settings');
    const refresh = (await page.context().cookies()).find(({ name }) => name === 'pa_refresh');
    expect(Boolean(refresh?.httpOnly && refresh.sameSite === 'Strict')).toBe(true);
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
    await page.getByRole('button', { name: 'Выйти' }).click();
    await expect(page.getByRole('heading', { name: 'Вход в Price Analyzer' })).toBeVisible();
    expect((await page.context().cookies()).some(({ name }) => name === 'pa_refresh')).toBe(false);
    const replay = await page.request.post('/api/auth/refresh', {
      headers: { ...browserAuthHeaders, Cookie: `pa_refresh=${refresh.value}` },
    });
    expect(replay.status()).toBe(401);
  } finally {
    await admin.close();
  }
});

test('пользователь добавляет, ищет, редактирует и удаляет товар', async ({ page }) => {
  await signIn(page);
  const name = uniqueName('E2E Каталог');
  const updated = `${name} обновлён`;
  await page.goto('/');
  await expect.poll(async () => {
    const response = await page.request.get('/api/health');
    return response.ok();
  }).toBe(true);
  await page.getByRole('button', { name: /Каталог товаров/ }).click();
  await page.getByRole('button', { name: /Новый товар/ }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Название').fill(name);
  await dialog.getByLabel('Категория').fill('Тестовая категория');
  await dialog.getByLabel('Единица').fill('шт.');
  await dialog.getByLabel('Описание').fill('Проверка сохранения описания');
  await dialog.getByRole('button', { name: 'Сохранить товар' }).click();
  await expect(page.getByRole('status')).toContainText('Товар добавлен');
  const search = page.getByRole('textbox', { name: /Поиск/ });
  await search.fill(name);
  const row = page.getByRole('row').filter({ hasText: name });
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: 'Изменить' }).click();
  await dialog.getByLabel('Название').fill(updated);
  await dialog.getByRole('button', { name: 'Сохранить товар' }).click();
  await search.fill(updated);
  const updatedRow = page.getByRole('row').filter({ hasText: updated });
  await expect(updatedRow).toBeVisible();
  await expect(updatedRow).toContainText('Проверка сохранения описания');
  page.once('dialog', async (alert) => alert.accept());
  await updatedRow.getByRole('button', { name: 'Удалить' }).click();
  await expect(page.getByRole('row').filter({ hasText: updated })).toHaveCount(0);
});

test('пользователь добавляет цены и сравнивает предложения', async ({ page }) => {
  await signIn(page);
  const name = uniqueName('E2E Сравнение');
  const res = await post(page.request, '/api/products', {
    data: { name, category: 'Тесты', unit: 'шт.' },
  });
  expect(res.status()).toBe(201);
  await page.goto('/');
  await expect.poll(async () => {
    const response = await page.request.get('/api/health');
    return response.ok();
  }).toBe(true);
  await page.getByRole('button', { name: /Сравнение цен/ }).click();
  await page.locator('.selector select').selectOption({ label: name });

  for (const [store, price, date] of [
    ['E2E Магазин А', '70.00', '2026-10-01'],
    ['E2E Магазин А', '115.00', '2026-10-14'],
    ['E2E Магазин А', '125.00', '2026-10-15'],
    ['E2E Магазин Б', '99.00', '2026-10-15'],
  ]) {
    await page.getByRole('button', { name: /Добавить цену/ }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Товар').selectOption({ label: name });
    await dialog.getByLabel('Магазин').fill(store);
    await dialog.getByLabel('Цена').fill(price);
    await dialog.getByLabel('Дата').fill(date);
    await dialog.getByRole('button', { name: 'Сохранить цену' }).click();
    await expect(page.getByRole('status')).toContainText('Цена сохранена');
  }
  await expect(page.getByRole('heading', { name: 'Сравнение магазинов' })).toBeVisible();
  const offers = page.getByRole('table').filter({
    has: page.getByRole('columnheader', { name: 'РАЗНИЦА', exact: true }),
  });
  await expect(offers.getByRole('row', { name: /E2E Магазин А/ })).toBeVisible();
  const bestOffer = offers.getByRole('row', { name: /E2E Магазин Б/ });
  await expect(bestOffer.getByRole('cell', { name: /^99,00\s*₽$/ })).toBeVisible();
  await page.getByLabel('Начало периода').fill('2026-10-15');
  await expect(page.getByRole('row').filter({ hasText: '2026-10-01' })).toHaveCount(0);
  await page.getByRole('button', { name: /Обзор/ }).click();
  const chart = page.getByRole('img', { name: /Линейный график динамики цен/ });
  await expect(chart).toBeVisible();
  await expect(chart.locator('circle')).toHaveCount(2);
  await chart.locator('circle').filter({ hasText: '99,00' }).focus();
  await expect(page.locator('.chart-probe')).toContainText(/99,00\s*₽.*2026-10-15/);
  await page.getByRole('button', { name: 'Сбросить период' }).click();
  await expect(chart.locator('circle')).toHaveCount(4);
  const dateTicks = chart.locator('text[y="264"]');
  await expect(dateTicks).toHaveText([/^1\D+окт/, /^15\D+окт/]);
});

async function seedComparison(admin) {
  await signIn(admin);
  async function addProduct(prefix) {
    const name = uniqueName(prefix);
    const product = await post(admin.request, '/api/products', {
      data: { name, category: 'Тесты', unit: 'шт.' },
    });
    expect(product.status()).toBe(201);
    return product.json();
  }
  const priced = await addProduct('E2E Контрольные цены');
  const empty = await addProduct('E2E Без цен');
  for (const [store, price, date] of [
    ['Контроль Маркет', 110, '2026-10-01'],
    ['Контроль маркет', 90, '2026-10-10'],
    ['Контроль Лавка', 90, '2026-10-10'],
    ['Контроль Лавка', 125, '2026-10-20'],
  ]) {
    const saved = await post(admin.request, '/api/prices', { data: { productId: priced.id, store, price, date } });
    expect(saved.status()).toBe(201);
  }
  return { priced, empty };
}

test('приёмка аналитики: последние цены, разница, равный минимум и границы периода', async ({ page, browser }) => {
  const admin = await browser.newPage();
  const { priced } = await seedComparison(admin);
  await admin.close();
  await createUser(page);
  await page.goto('/');
  await page.getByRole('button', { name: 'Сравнение цен' }).click();
  await page.getByLabel('Товар для сравнения').selectOption({ label: priced.name });
  const offers = page.getByRole('table').filter({ has: page.getByRole('columnheader', { name: 'РАЗНИЦА' }) });
  const rows = offers.locator('tbody tr');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText(/Контроль маркет.*90,00\s*₽/);
  await expect(rows.nth(1)).toContainText(/Контроль Лавка.*125,00\s*₽.*\+35,00\s*₽/);
  await expect(page.locator('.metric').filter({ hasText: 'Минимальная' })).toContainText(/90,00\s*₽/);
  await expect(page.locator('.metric').filter({ hasText: 'Средняя по магазинам' })).toContainText(/107,50\s*₽/);
  await expect(page.locator('.metric').filter({ hasText: 'Максимальная' })).toContainText(/125,00\s*₽/);
  await page.getByLabel('Начало периода').fill('2026-10-10');
  await page.getByLabel('Конец периода').fill('2026-10-10');
  await expect(rows).toHaveCount(2);
  await expect(offers.getByText('Минимум', { exact: true })).toHaveCount(2);
  await expect(offers).not.toContainText(/\+0,00/);

  await page.getByLabel('Конец периода').fill('2026-10-09');
  await expect(page.getByRole('alert')).toContainText(/дат/);
  await page.getByRole('button', { name: 'Сбросить период' }).click();
  await expect(rows.nth(1)).toContainText(/125,00\s*₽/);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('приёмка аналитики: одна линия на магазин, выбор периода и пустой график', async ({ page }) => {
  const { priced, empty } = await seedComparison(page);
  await page.goto('/');
  const selector = page.getByLabel('Товар для графика');
  await selector.selectOption({ label: priced.name });
  const chart = page.getByRole('img', { name: 'Линейный график динамики цен' });
  await expect(chart.locator('circle')).toHaveCount(4);
  await expect(chart.locator('polyline')).toHaveCount(2);
  await chart.locator('circle').filter({ hasText: /110,00/ }).focus();
  await expect(page.locator('.chart-probe')).toContainText(/Контроль маркет.*110,00\s*₽.*2026-10-01/i);
  await page.getByLabel('Начало периода').fill('2026-10-10');
  await page.getByLabel('Конец периода').fill('2026-10-10');
  await expect(chart.locator('circle')).toHaveCount(2);
  await selector.selectOption({ label: empty.name });
  await expect(page.getByText('Для выбранного периода пока нет цен.')).toBeVisible();
  await expect(chart).toHaveCount(0);
});

test('приёмка аналитики: при смене товара предыдущие цены не видны, пока загружается новый', async ({ page }) => {
  const { priced, empty } = await seedComparison(page);
  await page.goto('/');
  await page.getByRole('button', { name: 'Сравнение цен' }).click();
  const selector = page.getByLabel('Товар для сравнения');
  await selector.selectOption({ label: priced.name });
  const offers = page.getByRole('table').filter({ has: page.getByRole('columnheader', { name: 'РАЗНИЦА' }) });
  await expect(offers.locator('tbody tr')).toHaveCount(2);

  let allowResponse;
  const paused = new Promise((resolve) => { allowResponse = resolve; });
  const path = `**/api/analysis/${empty.id}`;
  await page.route(path, async (route) => { await paused; await route.continue(); });
  try {
    await selector.selectOption({ label: empty.name });
    await expect(offers.locator('tbody tr')).toHaveCount(0, { timeout: 1000 });
    await expect(page.getByText('Загружаем цены…')).toBeVisible();
  } finally {
    allowResponse();
  }
  await expect(page.getByText('Для выбранного товара нет цен.')).toBeVisible();
  await expect(offers.locator('tbody tr')).toHaveCount(0);
  await expect(page.locator('.metric strong')).toHaveText(['—', '—', '—']);
});

test('пользователь загружает CSV и скачивает историю', async ({ page }) => {
  await signIn(page);
  const name = uniqueName('E2E Импорт');
  const response = await post(page.request, '/api/products', {
    data: { name, category: 'Тесты', unit: 'шт.' },
  });
  expect(response.status()).toBe(201);
  const { id } = await response.json();
  await page.goto('/');
  await expect.poll(async () => {
    const response = await page.request.get('/api/health');
    return response.ok();
  }).toBe(true);
  await page.getByRole('button', { name: /Импорт \/ экспорт/ }).click();
  await page.locator('input[type="file"]').setInputFiles({
    name: 'test-prices.csv', mimeType: 'text/csv',
    buffer: Buffer.from(`productId,store,price,date\n${id},E2E CSV Магазин,147.25,2026-10-21\n`, 'utf8'),
  });
  await expect(page.getByRole('status')).toContainText('Импортировано: 1');
  await page.locator('.import-card select').selectOption({ value: String(id) });
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Скачать CSV' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('prices.csv');
  const fs = await import('node:fs/promises');
  const csv = await fs.readFile(await download.path(), 'utf8');
  expect(csv).toContain('E2E CSV Магазин');
  expect(csv).toContain('147.25');
});

test('пользователь формирует отчёт и выгружает PDF и Excel', async ({ page }) => {
  await signIn(page);
  const category = uniqueName('Категория отчёта');
  const product = await post(page.request, '/api/products', {
    data: { name: uniqueName('Товар отчёта'), category, unit: 'шт.' },
  });
  expect(product.status()).toBe(201);
  const productId = (await product.json()).id;
  for (const [price, date] of [[100, '2026-09-01'], [120, '2026-09-02']]) {
    expect((await post(page.request, '/api/prices', {
      data: { productId, store: 'Контрольный магазин', price, date },
    })).status()).toBe(201);
  }
  await page.goto('/');
  await page.getByRole('button', { name: 'Отчёты' }).click();
  await page.getByRole('combobox', { name: 'Категория', exact: true }).selectOption(category);
  await page.getByRole('button', { name: 'Сформировать отчёт' }).click();
  const row = page.getByRole('row').filter({ hasText: category });
  await expect(row).toContainText('20,00');
  for (const [label, filename] of [['Скачать PDF', 'price-report.pdf'], ['Скачать Excel', 'price-report.xlsx']]) {
    const pending = page.waitForEvent('download');
    await page.getByRole('button', { name: label }).click();
    expect((await pending).suggestedFilename()).toBe(filename);
  }
});
