export const normalizeIosChinaPrice = (value?: string | null): string | null => {
  const raw = value?.trim() || '';
  if (!/^(0|[1-9][0-9]{0,7})(\.[0-9]{1,2})?$/.test(raw) || Number(raw) <= 0) return null;
  const [whole, fraction = ''] = raw.split('.');
  return `${whole}.${fraction.padEnd(2, '0')}`;
};
