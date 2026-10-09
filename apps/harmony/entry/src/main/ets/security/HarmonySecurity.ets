import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { http } from '@kit.NetworkKit';
import { HarmonyDeviceSession } from '../network/HarmonyDeviceSession';
import { ApiRequestError, JsonHttp } from '../network/JsonHttp';
import { SecurityEvents } from './SecurityEvents';

interface SecurityState { allowed: boolean; checks: string[]; }
interface SecurityReport { signals: Record<string, string>; }

export class HarmonySecurity {
  private static readonly BASE: string = 'https://wallpaper.biguo66.top/api/v1';
  private context: common.UIAbilityContext;
  private sessions: HarmonyDeviceSession;
  private changed: (blocked: boolean, ready: boolean, busy: boolean) => void;
  blocked: boolean = false;
  ready: boolean = false;
  private started: boolean = false;
  private active: boolean = true;
  private alive: boolean = true;
  private busy: boolean = false;
  private pending: Promise<void> | null = null;
  private timer: number = -1;
  private epoch: number = 0;
  constructor(context: common.UIAbilityContext, sessions: HarmonyDeviceSession,
    changed: (blocked: boolean, ready: boolean, busy: boolean) => void) {
    this.context = context; this.sessions = sessions; this.changed = changed;
    SecurityEvents.onBlocked = () => this.markBlocked();
  }
  async start(): Promise<void> {
    if (!this.started) {
      this.started = true;
      try {
        const store: preferences.Preferences = await preferences.getPreferences(this.context, 'qingjing_security');
        this.blocked = Boolean(await store.get('blocked', false));
        SecurityEvents.blocked = this.blocked;
      } catch (error) { /* The server must still decide whether business can start. */ }
      this.emit();
    }
    await this.check();
  }
  private markBlocked(): void {
    if (!this.alive) return;
    this.epoch++; this.blocked = true; SecurityEvents.blocked = true;
    this.stop(); this.save(true); this.emit();
  }
  private emit(): void { if (this.alive) this.changed(this.blocked, this.ready, this.busy); }
  private async save(blocked: boolean): Promise<void> {
    try {
      const store: preferences.Preferences = await preferences.getPreferences(this.context, 'qingjing_security');
      await store.put('blocked', blocked); await store.flush();
    } catch (error) { /* Never clear a server restriction on storage failure. */ }
  }
  async check(): Promise<void> {
    if (!this.alive) return;
    if (this.pending !== null) return await (this.pending as Promise<void>);
    const operation: Promise<void> = this.performCheck(); this.pending = operation;
    try { await operation; } finally { this.pending = null; }
  }
  private async performCheck(): Promise<void> {
    this.stop(); this.busy = true; this.emit(); const epoch: number = this.epoch;
    try {
      let state: SecurityState = await this.readState();
      if (typeof state.allowed !== 'boolean' || !Array.isArray(state.checks)) throw new Error('Invalid state');
      if (state.allowed && state.checks.length > 0) {
        // No verified Harmony environment adapter is advertised by the backend yet.
        const signals: Record<string, string> = {};
        state.checks.forEach((key: string) => signals[key] = 'UNKNOWN');
        const report: SecurityReport = { signals: signals };
        state = await this.sessions.signedPost('/device/security/reports', report) as SecurityState;
        if (typeof state.allowed !== 'boolean') throw new Error('Invalid report');
      }
      if (!this.alive || epoch !== this.epoch) return;
      this.blocked = !state.allowed; this.ready = state.allowed; SecurityEvents.blocked = this.blocked;
      await this.save(this.blocked);
    } catch (error) { /* A failed check cannot clear a cached ban or start business. */ }
    finally {
      if (this.alive) {
        this.busy = false; this.emit();
        if (this.active && this.ready && !this.blocked) this.timer = setTimeout(() => { this.check(); }, 30000);
      }
    }
  }
  private async readState(): Promise<SecurityState> {
    for (let attempt: number = 0; attempt < 2; attempt++) {
      try {
        const token: string = await this.sessions.accessToken();
        return await JsonHttp.request(HarmonySecurity.BASE + '/device/security/state',
          http.RequestMethod.GET, null, token) as SecurityState;
      } catch (error) {
        if (attempt === 0 && error instanceof ApiRequestError && error.status === 401) {
          this.sessions.invalidate();
        } else { throw new Error(error instanceof Error ? error.message : 'Unable to check state'); }
      }
    }
    throw new Error('Unable to check state');
  }
  private stop(): void { if (this.timer >= 0) clearTimeout(this.timer); this.timer = -1; }
  setActive(active: boolean): void { this.active = active; if (!active) this.stop(); }
  dispose(): void { this.alive = false; this.stop(); SecurityEvents.onBlocked = null; }
}
