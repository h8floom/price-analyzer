import { describe, expect, it } from 'vitest';
import { calculateStats, getLatestByStore, getSeries, formatPrice, dateLabel } from './priceUtils.js';

describe('boundary cases of price analysis', () => {
  it('replaces an older offer with the latest one regardless of price', () => {
    const records = [
      { id: 1, store: 'А', date: '2026-10-01', price: 70 },
      { id: 2, store: 'А', date: '2026-10-02', price: 90 },
      { id: 3, store: 'Б', date: '2026-10-01', price: 80 },
    ];
    expect(getLatestByStore(records).map((entry) => entry.price)).toEqual([80, 90]);
  });

  it('resolves equal-date records using the newest id', () => {
    expect(getLatestByStore([
      { id: 2, store: 'А', date: '2026-10-01', price: 150 },
      { id: 3, store: 'А', date: '2026-10-01', price: 100 },
    ])[0].id).toBe(3);
  });

  it('returns null metrics on empty input', () => {
    expect(calculateStats([])).toEqual({ min: null, max: null, average: null, count: 0 });
  });

  it('uses unique dates and names for chart series', () => {
    expect(getSeries([
      { date: '2026-10-10', store: 'Б' },
      { date: '2026-10-01', store: 'А' },
      { date: '2026-10-10', store: 'А' },
    ])).toEqual({ dates: ['2026-10-01', '2026-10-10'], stores: ['А', 'Б'] });
  });

  it('formats missing and invalid data safely', () => {
    expect(formatPrice(null)).toBe('—');
    expect(formatPrice('not-a-number')).toBe('—');
    expect(dateLabel('invalid')).toBe('invalid');
  });
});
