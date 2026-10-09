export class SecurityEvents {
  static blocked: boolean = false;
  static onBlocked: (() => void) | null = null;
  static recovery(url: string): boolean {
    const path: string = url.split('?')[0];
    return path.indexOf('/device/security/') >= 0 || path.endsWith('/device/registrations') ||
      path.endsWith('/device/session-challenges') || path.endsWith('/device/sessions');
  }
  static requireAllowed(url: string): void {
    if (SecurityEvents.blocked && !SecurityEvents.recovery(url)) throw new Error('网络异常，请稍后重试');
  }
  static notifyBlocked(): void {
    SecurityEvents.blocked = true;
    if (SecurityEvents.onBlocked !== null) (SecurityEvents.onBlocked as () => void)();
  }
}
