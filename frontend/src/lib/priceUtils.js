
export function getProductPrices(records, productId) {
  return records
    .filter((record) => String(record.productId) === String(productId))
    .slice()
    .sort((a, b) => a.date.localeCompare(b.date));
}

export function calculatePriceStats(records) {
  if (!records.length) {
    return { min: null, max: null, average: null, count: 0 };
  }

  const values = records.map((record) => Number(record.price));
  const total = values.reduce((sum, price) => sum + price, 0);
  return {
    min: Math.min(...values),
    max: Math.max(...values),
    average: total / values.length,
    count: values.length,
  };
}

export function getLatestPricesByStore(records, productId) {
  const productRecords = getProductPrices(records, productId);
  const latestByStore = new Map();

  for (const record of productRecords) {
    const current = latestByStore.get(record.store);
    if (!current || record.date >= current.date) {
      latestByStore.set(record.store, record);
    }
  }

  return [...latestByStore.values()].sort((a, b) => a.price - b.price);
}
