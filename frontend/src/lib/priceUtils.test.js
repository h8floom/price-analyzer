import { describe, expect, it } from 'vitest';
import { calculatePriceStats, getLatestPricesByStore, getProductPrices } from './priceUtils.js';

const records = [
  { id: 1, productId: 1, store: 'Магазин А', price: 100, date: '2026-10-01' },
  { id: 2, productId: 1, store: 'Магазин А', price: 90, date: '2026-10-08' },
  { id: 3, productId: 1, store: 'Магазин Б', price: 110, date: '2026-10-08' },
  { id: 4, productId: 2, store: 'Магазин В', price: 50, date: '2026-10-08' },
];

describe('priceUtils', () => {
  it('filters product prices and sorts them by date', () => {
    expect(getProductPrices(records, 1).map((record) => record.id)).toEqual([1, 2, 3]);
  });

  it('calculates min, max, average and count', () => {
    expect(calculatePriceStats(records.slice(0, 3))).toEqual({
      min: 90,
      max: 110,
      average: 100,
      count: 3,
    });
  });

  it('returns empty statistics for an empty list', () => {
    expect(calculatePriceStats([])).toEqual({ min: null, max: null, average: null, count: 0 });
  });

  it('selects the latest price in every store and sorts by price', () => {
    expect(getLatestPricesByStore(records, 1).map((record) => [record.store, record.price])).toEqual([
      ['Магазин А', 90],
      ['Магазин Б', 110],
    ]);
  });
});
