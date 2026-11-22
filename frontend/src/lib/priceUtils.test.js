import { describe, it, expect } from 'vitest';
import { calculateStats, getLatestByStore, getSeries, formatPrice, dateLabel } from './priceUtils.js';

const records = [
  { id: 1, store: 'А', price: 100, date: '2026-10-01' },
  { id: 2, store: 'А', price: 90, date: '2026-10-08' },
  { id: 3, store: 'Б', price: 110, date: '2026-10-08' },
];
describe('price calculations', () => {
  it('handles empty input', () => expect(calculateStats([]).count).toBe(0));
  it('calculates min max average', () => expect(calculateStats(records).average).toBe(100));
  it('selects latest price per store', () => expect(getLatestByStore(records).map((x) => x.price)).toEqual([90, 110]));
  it('builds sorted date/store series', () => expect(getSeries(records).dates).toEqual(['2026-10-01', '2026-10-08']));
  it('formats prices', () => expect(formatPrice(199)).toContain('199'));
  it('preserves date label for invalid values', () => expect(dateLabel('invalid')).toBe('invalid'));
});
