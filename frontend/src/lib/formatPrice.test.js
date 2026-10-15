import { describe, expect, it } from 'vitest';
import { formatPrice } from './formatPrice.js';

describe('formatPrice', () => {
  it('formats a numeric price in Russian rubles', () => {
    expect(formatPrice(100)).toContain('100');
    expect(formatPrice(100)).toContain('₽');
  });

  it('rejects values that are not finite numbers', () => {
    expect(() => formatPrice(Number.NaN)).toThrow(TypeError);
    expect(() => formatPrice('100')).toThrow(TypeError);
  });
});
