import { deviceInfo } from '@kit.BasicServicesKit';
import { HarmonyDeviceSession } from '../network/HarmonyDeviceSession';

interface DeviceInformation { manufacturer: string; model: string; osVersion: string; }

export class DeviceActivity {
  private sessions: HarmonyDeviceSession;
  private allowed: () => boolean;
  private timer: number = -1;
  private running: boolean = false;
  private disposed: boolean = false;
  constructor(sessions: HarmonyDeviceSession, allowed: () => boolean) {
    this.sessions = sessions; this.allowed = allowed;
  }
  refresh(): void {
    if (this.timer >= 0) clearTimeout(this.timer);
    this.timer = -1;
    if (!this.disposed && this.allowed()) this.report();
  }
  dispose(): void {
    this.disposed = true;
    if (this.timer >= 0) clearTimeout(this.timer);
  }
  private async report(): Promise<void> {
    if (this.disposed || !this.allowed() || this.running) return;
    this.running = true;
    let delay: number = 30000;
    try {
      const information: DeviceInformation = {
        manufacturer: deviceInfo.manufacture.substring(0, 64),
        model: deviceInfo.productModel.substring(0, 96),
        osVersion: deviceInfo.osFullName.substring(0, 96)
      };
      await this.sessions.signedPost('/device/activity', information);
      delay = 60000;
    } catch (error) { /* Failed tracking retries independently of the business UI. */ }
    finally {
      this.running = false;
      if (!this.disposed && this.allowed()) this.timer = setTimeout(() => this.report(), delay);
    }
  }
}
