/// Shared by API callers. Recovery never grants access to business data.
class SecurityNetwork {
  static bool blocked = false;
  static void Function()? onBlocked;
  static bool isRecovery(String path) =>
      path.startsWith('/device/security/') ||
      const {
        '/device/registrations',
        '/device/session-challenges',
        '/device/sessions',
      }.contains(path);
  static void notifyBlocked() {
    blocked = true;
    onBlocked?.call();
  }
}
