import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { taskpool } from '@kit.ArkTS';
import { deviceInfo } from '@kit.BasicServicesKit';
import { safetyDetect } from '@kit.DeviceSecurityKit';
import { HarmonyDeviceSession } from '../network/HarmonyDeviceSession';

export interface HarmonyRiskState { allowed: boolean; checks: string[]; }
interface HarmonyRiskChallenge extends HarmonyRiskState { nonce: string | null; retryAfterSeconds: number; }
interface HarmonyRiskProof { nonce: string; jws: string; }

// Huawei requires the device/cloud collection to run outside the UI thread.
@Concurrent
async function queryHarmonyFactors(nonce: string, checks: string[]): Promise<string> {
  try {
    const queries: safetyDetect.FactorQuery[] = [];
    if (checks.indexOf('DEVELOPER_MODE') >= 0) queries.push({ factor: safetyDetect.RiskFactorType.IS_DEVELOPER_MODE });
    if (checks.indexOf('USB_DEBUGGING') >= 0) queries.push({ factor: safetyDetect.RiskFactorType.HDC_DEBUG_STATE });
    if (queries.length === 0) return '';
    const response: safetyDetect.RiskFactorResponse = await safetyDetect.queryRiskFactors({ nonce: nonce, queries: queries });
    return response.result;
  } catch (error) {
    // Unsupported versions, missing capability, cloud errors and quota exhaustion are unknown, never risk.
    return '';
  }
}

export class HarmonyRiskFactors {
  private context: common.UIAbilityContext;
  private sessions: HarmonyDeviceSession;
  private nextAttempt: number = 0;
  private previousChecks: string = '';
  private loaded: boolean = false;
  constructor(context: common.UIAbilityContext, sessions: HarmonyDeviceSession) {
    this.context = context; this.sessions = sessions;
  }
  async inspect(checks: string[]): Promise<HarmonyRiskState | null> {
    // Keep HarmonyOS 6/API20 compatibility. Never resolve API26 factor enums on older systems.
    if (deviceInfo.sdkApiVersion < 26) return null;
    const requested: string[] = checks.filter((key: string) => key === 'DEVELOPER_MODE' || key === 'USB_DEBUGGING').sort();
    const signature: string = requested.join(',');
    const store: preferences.Preferences = await preferences.getPreferences(this.context, 'qingjing_harmony_risk');
    if (!this.loaded) {
      this.nextAttempt = Number(await store.get('next_attempt', 0));
      this.previousChecks = String(await store.get('previous_checks', ''));
      this.loaded = true;
    }
    if (signature.length === 0) {
      this.previousChecks = ''; await store.put('previous_checks', ''); await store.flush(); return null;
    }
    if (signature === this.previousChecks && Date.now() < this.nextAttempt) return null;
    // Persist before requesting: restarting the process must not consume the cloud quota repeatedly.
    await this.schedule(store, signature, 600);
    const challenge: HarmonyRiskChallenge = await this.sessions.signedPost('/device/security/harmony/challenges', new Object()) as HarmonyRiskChallenge;
    if (typeof challenge.allowed !== 'boolean' || !Array.isArray(challenge.checks) ||
      !Number.isFinite(challenge.retryAfterSeconds) || challenge.retryAfterSeconds < 0) throw new Error('Invalid risk challenge');
    if (!challenge.allowed) return { allowed: false, checks: [] };
    if (challenge.nonce === null || challenge.checks.length === 0) {
      await this.schedule(store, signature, Math.max(60, challenge.retryAfterSeconds)); return null;
    }
    if (typeof challenge.nonce !== 'string' || !/^[A-Za-z0-9+/]{43}=$/.test(challenge.nonce) ||
      challenge.checks.some((key: string) => requested.indexOf(key) < 0)) throw new Error('Invalid requested risk factors');
    const jws: string = await taskpool.execute(new taskpool.Task(queryHarmonyFactors, challenge.nonce, challenge.checks)) as string;
    if (typeof jws !== 'string' || jws.length === 0 || jws.length > 32768) return null;
    // Forward the signed evidence intact. The client never interprets it as authority to ban.
    const proof: HarmonyRiskProof = { nonce: challenge.nonce, jws: jws };
    const state: HarmonyRiskState = await this.sessions.signedPost('/device/security/harmony/reports', proof) as HarmonyRiskState;
    if (typeof state.allowed !== 'boolean' || !Array.isArray(state.checks)) throw new Error('Invalid risk result');
    await this.schedule(store, signature, Math.max(5400, challenge.retryAfterSeconds));
    return state;
  }
  private async schedule(store: preferences.Preferences, checks: string, seconds: number): Promise<void> {
    this.nextAttempt = Date.now() + seconds * 1000; this.previousChecks = checks;
    await store.put('next_attempt', this.nextAttempt); await store.put('previous_checks', checks); await store.flush();
  }
}
