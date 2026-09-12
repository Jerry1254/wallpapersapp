import type { DeviceChallenge, DeviceCredential, DeviceSession } from '@/domain/device';
import { ApiClientError, apiRequest, type ApiRequestOptions } from './apiClient';

export const deviceStorageKey = 'qingjing-h5-test-device-v1';
type Transport = <T>(path: string, options?: ApiRequestOptions) => Promise<T>;
interface DeviceIdentity extends Partial<DeviceCredential> { evidenceToken: string }
const bytes = (value: string) => new TextEncoder().encode(value);
export const instantTimestamp = (date: Date) => date.toISOString().replace(/\.000Z$/, 'Z');
export const sha256Hex = async (value: string) => Array.from(new Uint8Array(
  await crypto.subtle.digest('SHA-256', bytes(value))
)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
export const hmacBase64Url = async (secret: string, payload: string) => {
  const key = await crypto.subtle.importKey('raw', bytes(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signature = new Uint8Array(await crypto.subtle.sign('HMAC', key, bytes(payload)));
  return btoa(String.fromCharCode(...signature)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

// This provider is deliberately restricted to the local H5_TEST contract.
// Native apps will supply platform evidence and protected private keys instead.
export class H5DeviceProvider {
  private session?: DeviceSession;
  private identity?: DeviceIdentity;
  private sessionFlight?: Promise<DeviceSession>;

  constructor(private readonly storage: Storage, private readonly transport: Transport = apiRequest) {}

  private save(identity: DeviceIdentity) {
    try { this.storage.setItem(deviceStorageKey, JSON.stringify(identity)); }
    catch { throw new Error('浏览器无法保存测试设备，请允许此站点使用本地存储后重试'); }
    this.identity = identity;
  }

  private loadIdentity() {
    if (this.identity) return this.identity;
    let saved: DeviceIdentity | undefined;
    try {
      const value = this.storage.getItem(deviceStorageKey);
      if (value) saved = JSON.parse(value) as DeviceIdentity;
    } catch { throw new Error('浏览器测试设备数据不可用，请检查此站点的存储权限'); }
    if (saved && typeof saved.evidenceToken === 'string' && saved.evidenceToken.length >= 16) {
      this.identity = saved;
    } else {
      this.save({ evidenceToken: crypto.randomUUID() + crypto.randomUUID() });
    }
    return this.identity!;
  }

  private async register(identity: DeviceIdentity) {
    const credential = await this.transport<DeviceCredential>('/device/registrations', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ platform: 'H5_TEST', appInstallScope: 'h5-local', credentialType: 'H5_TEST_SECRET', evidenceToken: identity.evidenceToken })
    });
    this.save({ evidenceToken: identity.evidenceToken, ...credential });
    return this.identity!;
  }

  private async createSession() {
    let identity = this.loadIdentity();
    if (!identity.credentialKeyId || !identity.credentialSecret) identity = await this.register(identity);
    let challenge: DeviceChallenge;
    const getChallenge = (keyId: string) => this.transport<DeviceChallenge>('/device/session-challenges', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ credentialKeyId: keyId })
    });
    try { challenge = await getChallenge(identity.credentialKeyId!); }
    catch (error) {
      if (!(error instanceof ApiClientError) || error.code !== 'CREDENTIAL_NOT_FOUND') throw error;
      // Preserve the evidence so a locally revoked test credential rejoins the same device.
      identity = await this.register(identity);
      challenge = await getChallenge(identity.credentialKeyId!);
    }
    if (challenge.algorithm !== 'HMAC_SHA256') throw new Error('当前浏览器不支持此设备验证方式');
    const clientTimestamp = instantTimestamp(new Date());
    const proof = await hmacBase64Url(identity.credentialSecret!,
      `QJ-DEVICE-SESSION-V1\n${identity.credentialKeyId}\n${challenge.challengeId}\n${challenge.nonce}\n${clientTimestamp}`);
    const session = await this.transport<DeviceSession>('/device/sessions', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ credentialKeyId: identity.credentialKeyId, challengeId: challenge.challengeId, clientTimestamp, proof })
    });
    if (session.platform !== 'H5_TEST' || session.tokenType !== 'Bearer') throw new Error('设备验证返回了不支持的会话');
    this.session = session;
    return session;
  }

  async ensureSession() {
    if (this.session && Date.parse(this.session.expiresAt) > Date.now() + 15000) return this.session;
    if (!this.sessionFlight) this.sessionFlight = this.createSession().finally(() => { this.sessionFlight = undefined; });
    return this.sessionFlight;
  }

  async request<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    const send = async () => {
      const session = await this.ensureSession();
      const headers: Record<string, string> = { ...options.headers, Authorization: `Bearer ${session.accessToken}` };
      if (options.method === 'POST') {
        const timestamp = instantTimestamp(new Date());
        const nonce = crypto.randomUUID();
        const payload = `QJ-SIGNED-REQUEST-V1\nPOST\n/api/v1${path}\n${timestamp}\n${nonce}\n${await sha256Hex(options.body ?? '')}`;
        headers['X-Request-Timestamp'] = timestamp;
        headers['X-Request-Nonce'] = nonce;
        headers['X-Request-Signature'] = await hmacBase64Url(this.identity!.credentialSecret!, payload);
      }
      return this.transport<T>(path, { ...options, headers });
    };
    try { return await send(); }
    catch (error) {
      if (!(error instanceof ApiClientError) || error.status !== 401 || !['SESSION_EXPIRED', 'UNAUTHORIZED', 'CREDENTIAL_REVOKED'].includes(error.code)) throw error;
      this.session = undefined;
      // One renewal only; signed retries use the exact body and a fresh nonce.
      return send();
    }
  }
}

let provider: H5DeviceProvider | undefined;
export const getH5DeviceProvider = () => provider ??= new H5DeviceProvider(window.localStorage);

export const deviceErrorMessage = (error: unknown) => {
  if (error instanceof ApiClientError) {
    if (error.status === 429) return '操作较频繁，请稍后重试';
    if (error.status === 401) return '设备验证未完成，请重新尝试';
    if (error.status === 403) return error.code === 'ENTITLEMENT_REQUIRED' ? '当前浏览器尚未获得这张壁纸' : '当前环境暂不支持此操作';
    if (error.status >= 500) return '服务暂时不可用，请稍后重试';
    if (error.status === 0) return '暂时无法连接服务，请检查网络后重试';
    return '请求未完成，请稍后重试';
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试';
};
