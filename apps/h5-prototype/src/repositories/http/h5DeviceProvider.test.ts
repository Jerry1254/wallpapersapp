import { createHash, createHmac } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import { memoryStorage } from '@/test/storage';
import { ApiClientError, type ApiRequestOptions } from './apiClient';
import { H5DeviceProvider, deviceStorageKey, hmacBase64Url, instantTimestamp, sha256Hex } from './h5DeviceProvider';

const credential = { credentialKeyId: 'test-key', credentialSecret: 'a-test-only-credential-value' };
const challenge = { challengeId: 'test-challenge', nonce: 'test-nonce', algorithm: 'HMAC_SHA256' };
const session = { accessToken: 'a-test-only-session-value', expiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', platform: 'H5_TEST' };

describe('H5 test device proof', () => {
  it('matches independent UTF-8 HMAC/SHA256 vectors and Java Instant formatting', async () => {
    const payload = 'QJ-DEVICE-SESSION-V1\n测试\n2026-09-12T10:00:00Z';
    expect(await hmacBase64Url(credential.credentialSecret, payload)).toBe(createHmac('sha256', credential.credentialSecret).update(payload).digest('base64url'));
    expect(await sha256Hex(payload)).toBe(createHash('sha256').update(payload).digest('hex'));
    expect(instantTimestamp(new Date('2026-09-12T10:00:00.000Z'))).toBe('2026-09-12T10:00:00Z');
    expect(instantTimestamp(new Date('2026-09-12T10:00:00.120Z'))).toBe('2026-09-12T10:00:00.120Z');
  });

  it('registers once across concurrent requests and a reload, without persisting the session', async () => {
    const storage = memoryStorage();
    const transport = vi.fn(async (path: string, options?: ApiRequestOptions) => {
      if (path.endsWith('/registrations')) return credential;
      if (path.endsWith('/session-challenges')) return challenge;
      if (path.endsWith('/sessions')) {
        const body = JSON.parse(options!.body!);
        const payload = `QJ-DEVICE-SESSION-V1\n${credential.credentialKeyId}\n${challenge.challengeId}\n${challenge.nonce}\n${body.clientTimestamp}`;
        expect(body.proof).toBe(createHmac('sha256', credential.credentialSecret).update(payload).digest('base64url'));
        return session;
      }
      return { items: [] };
    });
    const provider = new H5DeviceProvider(storage, transport as never);
    await Promise.all([provider.request('/device/me/entitlements'), provider.request('/device/me/entitlements')]);
    await new H5DeviceProvider(storage, transport as never).request('/device/me/entitlements');
    expect(transport.mock.calls.filter(([path]) => path.endsWith('/registrations'))).toHaveLength(1);
    expect(transport.mock.calls.filter(([path]) => path.endsWith('/sessions'))).toHaveLength(2);
    expect(storage.getItem(deviceStorageKey)).not.toContain(session.accessToken);
    expect(storage.getItem(deviceStorageKey)).not.toContain('expiresAt');
  });

  it('renews an expired server session once, signing the same exact body with a new nonce', async () => {
    const storage = memoryStorage();
    storage.setItem(deviceStorageKey, JSON.stringify({ evidenceToken: 'a-stable-test-evidence', ...credential }));
    let attempts = 0;
    const body = JSON.stringify({ wallpaperId: '9007199254740993', code: 'ABCDEFGHIJKLMNOPQRST' });
    const transport = vi.fn(async (path: string, options?: ApiRequestOptions) => {
      if (path.endsWith('/session-challenges')) return challenge;
      if (path.endsWith('/sessions')) return session;
      const headers = options!.headers!;
      const payload = `QJ-SIGNED-REQUEST-V1\nPOST\n/api/v1${path}\n${headers['X-Request-Timestamp']}\n${headers['X-Request-Nonce']}\n${createHash('sha256').update(body).digest('hex')}`;
      expect(headers['X-Request-Signature']).toBe(createHmac('sha256', credential.credentialSecret).update(payload).digest('base64url'));
      if (++attempts === 1) throw new ApiClientError(401, 'SESSION_EXPIRED', 'expired');
      return { result: 'GRANTED' };
    });
    await new H5DeviceProvider(storage, transport as never).request('/device/redemptions', { method: 'POST', body, headers: { 'Idempotency-Key': 'same-key' } });
    const writes = transport.mock.calls.filter(([path]) => path.endsWith('/redemptions'));
    expect(writes).toHaveLength(2);
    expect(writes[0]![1]!.body).toBe(writes[1]![1]!.body);
    expect(writes[0]![1]!.headers!['Idempotency-Key']).toBe(writes[1]![1]!.headers!['Idempotency-Key']);
    expect(writes[0]![1]!.headers!['X-Request-Nonce']).not.toBe(writes[1]![1]!.headers!['X-Request-Nonce']);
  });

  it('preserves evidence when renewing a revoked local credential and stops after a second 401', async () => {
    const storage = memoryStorage();
    storage.setItem(deviceStorageKey, JSON.stringify({ evidenceToken: 'a-stable-test-evidence', ...credential }));
    let challenges = 0;
    const transport = vi.fn(async (path: string, options?: ApiRequestOptions) => {
      if (path.endsWith('/session-challenges')) {
        if (++challenges === 1) throw new ApiClientError(404, 'CREDENTIAL_NOT_FOUND', 'missing');
        return challenge;
      }
      if (path.endsWith('/registrations')) {
        expect(JSON.parse(options!.body!).evidenceToken).toBe('a-stable-test-evidence');
        return credential;
      }
      if (path.endsWith('/sessions')) return session;
      throw new ApiClientError(401, 'SESSION_EXPIRED', 'expired');
    });
    await expect(new H5DeviceProvider(storage, transport as never).request('/device/me/entitlements')).rejects.toMatchObject({ status: 401 });
    expect(transport.mock.calls.filter(([path]) => path.endsWith('/me/entitlements'))).toHaveLength(2);
    expect(challenges).toBe(3);
  });
});
