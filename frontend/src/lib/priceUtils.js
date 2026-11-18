export function formatPrice(price) {
  if (price === null || price === undefined || price === '') return '—';
  const num = Number(price);
  if (!Number.isFinite(num)) return '—';
  return new Intl.NumberFormat('ru-RU', {
    style: 'currency', currency: 'RUB', maximumFractionDigits: 2,
  }).format(num);
}

export function getLatestByStore(records) {
  const latest = new Map();
  for (const record of records) {
    const store = record.store.toLowerCase();
    const old = latest.get(store);
    if (!old || record.date > old.date || (record.date === old.date && Number(record.id) > Number(old.id))) {
      latest.set(store, record);
    }
  }
  return [...latest.values()].sort((a, b) => Number(a.price) - Number(b.price));
}

export function calculateStats(records) {
  if (!records.length) return { min: null, max: null, average: null, count: 0 };
  const values = records.map((x) => Number(x.price));
  const total = values.reduce((sum, value) => sum + value, 0);
  return {
    min: Math.min(...values), max: Math.max(...values),
    average: Math.round(total * 100 / values.length) / 100,
    count: values.length,
  };
}

export function getSeries(records) {
  const dates = [...new Set(records.map((item) => item.date))].sort();
  const stores = [...new Set(records.map((item) => item.store))].sort();
  return { dates, stores };
}

export function dateLabel(date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return date;
  return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short' })
    .format(new Date(`${date}T12:00:00`));
}
