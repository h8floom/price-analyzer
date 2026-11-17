import test from 'node:test';
import assert from 'node:assert/strict';
import { calculateStats, getLatestByStore, getSeries, formatPrice, dateLabel } from './priceUtils.js';
const records = [
  { id: 1, store: 'А', price: 100, date: '2026-10-01' },
  { id: 2, store: 'А', price: 90, date: '2026-10-08' },
  { id: 3, store: 'Б', price: 110, date: '2026-10-08' },
];
test('empty statistics', () => assert.equal(calculateStats([]).count, 0));
test('min/max/mean', () => assert.deepEqual(calculateStats(records), { min: 90, max: 110, average: 100, count: 3 }));
test('latest-by-store', () => assert.deepEqual(getLatestByStore(records).map((x) => x.price), [90, 110]));
test('dates and stores are unique', () => assert.deepEqual(getSeries(records).dates, ['2026-10-01', '2026-10-08']));
test('format rubles', () => assert.match(formatPrice(199), /199/));
test('handles invalid date label', () => assert.equal(dateLabel('invalid'), 'invalid'));
