/** Форматирует цену в рублях для отображения в интерфейсе. */
export function formatPrice(value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new TypeError('Цена должна быть конечным числом');
  }

  return new Intl.NumberFormat('ru-RU', {
    style: 'currency',
    currency: 'RUB',
    maximumFractionDigits: 2,
  }).format(value);
}
