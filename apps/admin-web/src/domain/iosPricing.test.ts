import { expect, it } from 'vitest';
import { normalizeIosChinaPrice } from './iosPricing';

it('preserves monetary precision and normalizes admin input to two decimals', () => {
  expect(normalizeIosChinaPrice('0.1')).toBe('0.10');
  expect(normalizeIosChinaPrice(' 18.8 ')).toBe('18.80');
  expect(normalizeIosChinaPrice('1')).toBe('1.00');
  expect(normalizeIosChinaPrice('99999999.99')).toBe('99999999.99');
});

it('rejects invalid prices before they reach the API', () => {
  for (const value of [null, '', '0', '0.00', '-1', '01.00', '1.005', '1e2', '¥1', '100000000.00']) {
    expect(normalizeIosChinaPrice(value)).toBeNull();
  }
});
