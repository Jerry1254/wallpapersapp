import { afterEach, expect, it, vi } from 'vitest';
import { setCsrfToken } from './apiClient';
import { securityRepository as api, securityTime, securityEventText, type SecurityBan } from './securityRepository';
afterEach(() => vi.unstubAllGlobals());
it('requires administrator CSRF and explicitly versions permanent ban releases', async () => {
  setCsrfToken('security-csrf-test');
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', fetchMock);
  await api.ban(' 291 ', ' 203.0.113.9 ', ' 手动封禁 ');
  const ban = { id: '7', version: 3 } as SecurityBan;
  await api.release(ban, ' 客服核验通过 ', true);
  const create = fetchMock.mock.calls[0]![1] as RequestInit;
  expect(JSON.parse(String(create.body))).toEqual({ deviceId: '291', ip: '203.0.113.9', reason: '手动封禁' });
  const release = fetchMock.mock.calls[1]![1] as RequestInit;
  expect(new Headers(release.headers).get('X-CSRF-Token')).toBe('security-csrf-test');
  expect(release.credentials).toBe('include');
  expect(JSON.parse(String(release.body))).toEqual({ version: 3, reason: '客服核验通过', includeRelated: true });
  expect(fetchMock.mock.calls[1]![0]).toContain('/security/bans/7/release');
});
it('shows precise Beijing time and handles missing or malformed audit summaries', () => {
  expect(securityTime('2026-10-09T08:04:05Z')).toContain('16:04:05');
  expect(securityTime('2026-10-09T08:04:05Z')).toContain('2026');
  expect(securityTime()).toBe('—');
  const base = { id: '1', action: 'PUT_SECURITY', result: 'SUCCEEDED', createdAt: '2026-10-09T08:04:05Z' };
  expect(securityEventText({ ...base, changes: 'invalid' })).toBe('安全风控操作');
  expect(securityEventText({ ...base, changes: JSON.stringify({ enabled: false }) })).toContain('历史封禁继续生效');
});
