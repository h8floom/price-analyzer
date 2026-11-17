import { test, expect } from '@playwright/test';

function uniqueName(prefix) {
  return `${prefix} ${Date.now()}-${Math.round(Math.random() * 1_000_000)}`;
}

test('пользователь добавляет, ищет, редактирует и удаляет товар', async ({ page }) => {
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
  page.once('dialog', async (alert) => alert.accept());
  await updatedRow.getByRole('button', { name: 'Удалить' }).click();
  await expect(page.getByRole('row').filter({ hasText: updated })).toHaveCount(0);
});

test('пользователь добавляет две цены и сравнивает предложения', async ({ page, request }) => {
  const name = uniqueName('E2E Сравнение');
  const res = await request.post('http://127.0.0.1:8080/api/products', {
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

  for (const [store, price] of [['E2E Магазин А', '125.00'], ['E2E Магазин Б', '99.00']]) {
    await page.getByRole('button', { name: /Добавить цену/ }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Товар').selectOption({ label: name });
    await dialog.getByLabel('Магазин').fill(store);
    await dialog.getByLabel('Цена').fill(price);
    await dialog.getByLabel('Дата').fill('2026-10-15');
    await dialog.getByRole('button', { name: 'Сохранить цену' }).click();
    await expect(page.getByRole('status')).toContainText('Цена сохранена');
  }
  await expect(page.getByRole('heading', { name: 'Сравнение магазинов' })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: 'E2E Магазин А' }).last()).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: 'E2E Магазин Б' }).last()).toBeVisible();
  await expect(page.getByText('99', { exact: false }).first()).toBeVisible();
  await page.getByRole('button', { name: /Обзор/ }).click();
  await expect(page.getByRole('img', { name: /Линейный график динамики цен/ })).toBeVisible();
});

test('пользователь загружает CSV и скачивает историю', async ({ page, request }) => {
  const name = uniqueName('E2E Импорт');
  const response = await request.post('http://127.0.0.1:8080/api/products', {
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
  await page.getByRole('link', { name: 'Скачать CSV' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('prices.csv');
  const fs = await import('node:fs/promises');
  const csv = await fs.readFile(await download.path(), 'utf8');
  expect(csv).toContain('E2E CSV Магазин');
  expect(csv).toContain('147.25');
});
