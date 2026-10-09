import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { huks } from '@kit.UniversalKeystoreKit';
import { http } from '@kit.NetworkKit';
import { ApiRequestError, JsonHttp } from './JsonHttp';

interface RegistrationResponse {
  credentialKeyId: string;
}

interface ChallengeResponse {
  challengeId: string;
  nonce: string;
  algorithm: string;
}

interface SessionResponse {
  accessToken: string;
}

interface RegistrationEvidence {
  timestamp: string;
  nonce: string;
  proof: string;
}

interface DeviceRegistrationRequest {
  platform: string;
  appInstallScope: string;
  credentialType: string;
  publicKeyPem: string;
  evidenceToken: string;
}

interface ChallengeRequest {
  credentialKeyId: string;
}

interface SessionRequest {
  credentialKeyId: string;
  challengeId: string;
  clientTimestamp: string;
  proof: string;
}

interface FailureWithCode {
  code: number;
}

export class HarmonyDeviceSession {
  private static readonly BASE_URL: string = 'https://wallpaper.biguo66.top/api/v1';
  private static readonly KEY_ALIAS: string = 'qingjing_harmony_device_identity_v1';
  private static readonly PREFERENCES_NAME: string = 'qingjing_device_session';
  private static readonly CREDENTIAL_ID: string = 'credential_key_id';
  private context: common.UIAbilityContext;
  private accessTokenValue: string = '';

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  async accessToken(): Promise<string> {
    if (this.accessTokenValue.length > 0) return this.accessTokenValue;
    const store: preferences.Preferences = await preferences.getPreferences(
      this.context, HarmonyDeviceSession.PREFERENCES_NAME);
    let credentialKeyId: string = String(await store.get(HarmonyDeviceSession.CREDENTIAL_ID, ''));
    if (credentialKeyId.length === 0) {
      credentialKeyId = await this.register(store);
    }
    try {
      this.accessTokenValue = await this.createSession(credentialKeyId);
      return this.accessTokenValue;
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 404) {
        credentialKeyId = await this.register(store);
        this.accessTokenValue = await this.createSession(credentialKeyId);
        return this.accessTokenValue;
      }
      if (error instanceof Error) throw error;
      throw new Error('鸿蒙设备身份验证失败');
    }
  }

  invalidate(): void {
    this.accessTokenValue = '';
  }
  async supportCacheIdentity(): Promise<string> {
    await this.accessToken();
    const store: preferences.Preferences = await preferences.getPreferences(this.context, HarmonyDeviceSession.PREFERENCES_NAME);
    return String(await store.get(HarmonyDeviceSession.CREDENTIAL_ID, '')).replace(/[^a-zA-Z0-9_-]/g, '_');
  }

  async signedPost(path: string, body: Object, extraHeaders: Record<string, string> = {},
    acceptedStatuses: number[] = []): Promise<Object> {
    const rawBody: string = JSON.stringify(body);
    for (let attempt: number = 0; attempt < 2; attempt++) {
      const token: string = await this.accessToken();
      const timestamp: string = new Date().toISOString();
      const nonce: string = util.generateRandomUUID();
      const bodyHash: string = await this.sha256Hex(new util.TextEncoder().encodeInto(rawBody));
      const canonicalPath: string = '/api/v1' + path.split('?')[0];
      const payload: string = 'QJ-SIGNED-REQUEST-V1\nPOST\n' + canonicalPath + '\n' + timestamp + '\n' + nonce +
        '\n' + bodyHash;
      const headers: Record<string, string> = {
        'X-Request-Timestamp': timestamp,
        'X-Request-Nonce': nonce,
        'X-Request-Signature': this.base64Url(await this.sign(payload))
      };
      Object.keys(extraHeaders).forEach((key: string) => headers[key] = extraHeaders[key]);
      try {
        return await JsonHttp.request(HarmonyDeviceSession.BASE_URL + path, http.RequestMethod.POST,
          rawBody, token, headers, acceptedStatuses);
      } catch (error) {
        if (error instanceof ApiRequestError && error.status === 401 && attempt === 0) {
          this.invalidate();
          continue;
        }
        if (error instanceof Error) throw error;
        throw new Error('鸿蒙设备签名请求失败');
      }
    }
    throw new Error('鸿蒙设备会话已失效，请重试');
  }

  private async register(store: preferences.Preferences): Promise<string> {
    await this.ensureKey();
    const exported: huks.HuksReturnResult = await huks.exportKeyItem(
      HarmonyDeviceSession.KEY_ALIAS, { properties: [] });
    if (exported.outData === undefined) throw new Error('无法读取鸿蒙设备公钥');
    const publicKey: Uint8Array = exported.outData;
    const fingerprint: string = await this.sha256Hex(publicKey);
    const timestamp: string = new Date().toISOString();
    const nonce: string = util.generateRandomUUID();
    const payload: string = 'QJ-HARMONYOS-REGISTER-V1\ncom.qingjing.bizhi\n' + fingerprint + '\n' +
      timestamp + '\n' + nonce;
    const proof: string = this.base64Url(await this.sign(payload));
    const evidence: RegistrationEvidence = { timestamp: timestamp, nonce: nonce, proof: proof };
    const registrationRequest: DeviceRegistrationRequest = {
      platform: 'HARMONYOS',
      appInstallScope: 'com.qingjing.bizhi',
      credentialType: 'PLATFORM_PUBLIC_KEY',
      publicKeyPem: this.publicKeyPem(publicKey),
      evidenceToken: this.base64Url(new util.TextEncoder().encodeInto(JSON.stringify(evidence)))
    };
    const response: Object = await JsonHttp.request(
      HarmonyDeviceSession.BASE_URL + '/device/registrations',
      http.RequestMethod.POST,
      registrationRequest);
    const registration: RegistrationResponse = response as RegistrationResponse;
    await store.put(HarmonyDeviceSession.CREDENTIAL_ID, registration.credentialKeyId);
    await store.flush();
    return registration.credentialKeyId;
  }

  private async createSession(credentialKeyId: string): Promise<string> {
    const challengeRequest: ChallengeRequest = { credentialKeyId: credentialKeyId };
    const challengeObject: Object = await JsonHttp.request(
      HarmonyDeviceSession.BASE_URL + '/device/session-challenges',
      http.RequestMethod.POST,
      challengeRequest);
    const challenge: ChallengeResponse = challengeObject as ChallengeResponse;
    if (challenge.algorithm !== 'RSA_SHA256') throw new Error('服务端返回了不支持的设备验证算法');
    const timestamp: string = new Date().toISOString();
    const payload: string = 'QJ-DEVICE-SESSION-V1\n' + credentialKeyId + '\n' + challenge.challengeId + '\n' +
      challenge.nonce + '\n' + timestamp;
    const sessionRequest: SessionRequest = {
      credentialKeyId: credentialKeyId,
      challengeId: challenge.challengeId,
      clientTimestamp: timestamp,
      proof: this.base64Url(await this.sign(payload))
    };
    const sessionObject: Object = await JsonHttp.request(
      HarmonyDeviceSession.BASE_URL + '/device/sessions',
      http.RequestMethod.POST,
      sessionRequest);
    return (sessionObject as SessionResponse).accessToken;
  }

  private async ensureKey(): Promise<void> {
    let exists: boolean = false;
    try {
      exists = await huks.isKeyItemExist(HarmonyDeviceSession.KEY_ALIAS, { properties: [] });
    } catch (error) {
      const failure: FailureWithCode = error as FailureWithCode;
      if (failure.code !== 12000011) {
        if (error instanceof Error) throw error;
        throw new Error('无法检查鸿蒙设备密钥');
      }
    }
    if (exists) return;
    await huks.generateKeyItem(HarmonyDeviceSession.KEY_ALIAS, {
      properties: [
        { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_RSA },
        { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_SIGN },
        { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_RSA_KEY_SIZE_2048 },
        { tag: huks.HuksTag.HUKS_TAG_DIGEST, value: huks.HuksKeyDigest.HUKS_DIGEST_SHA256 },
        { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_PKCS1_V1_5 }
      ]
    });
  }

  private async sign(payload: string): Promise<Uint8Array> {
    const options: huks.HuksOptions = {
      properties: [
        { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_RSA },
        { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_SIGN },
        { tag: huks.HuksTag.HUKS_TAG_DIGEST, value: huks.HuksKeyDigest.HUKS_DIGEST_SHA256 },
        { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_PKCS1_V1_5 }
      ],
      inData: new util.TextEncoder().encodeInto(payload)
    };
    const session: huks.HuksSessionHandle = await huks.initSession(HarmonyDeviceSession.KEY_ALIAS, options);
    const result: huks.HuksReturnResult = await huks.finishSession(session.handle, options);
    if (result.outData === undefined) throw new Error('鸿蒙设备签名失败');
    return result.outData;
  }

  private async sha256Hex(value: Uint8Array): Promise<string> {
    const digest: cryptoFramework.Md = cryptoFramework.createMd('SHA256');
    await digest.update({ data: value });
    const output: cryptoFramework.DataBlob = await digest.digest();
    let result: string = '';
    output.data.forEach((item: number) => {
      result += item.toString(16).padStart(2, '0');
    });
    return result;
  }

  private base64Url(value: Uint8Array): string {
    return new util.Base64Helper().encodeToStringSync(value, util.Type.BASIC_URL_SAFE).replace(/=/g, '');
  }

  private publicKeyPem(value: Uint8Array): string {
    const encoded: string = new util.Base64Helper().encodeToStringSync(value, util.Type.BASIC);
    let body: string = '';
    for (let index: number = 0; index < encoded.length; index += 64) {
      body += encoded.substring(index, Math.min(index + 64, encoded.length)) + '\n';
    }
    return '-----BEGIN PUBLIC KEY-----\n' + body + '-----END PUBLIC KEY-----';
  }
}
