import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'app_branding.dart';

class AppConfig {
  AppConfig({
    required String environment,
    required this.apiBase,
    required bool debug,
  }) : environment = environment == 'offline' ? 'prod' : environment,
       branding = AppBranding.forFlavor(environment) {
    if (!{
          'local',
          'internal',
          'lab',
          'prod',
          'offline',
        }.contains(environment) ||
        apiBase.host.isEmpty ||
        apiBase.userInfo.isNotEmpty ||
        apiBase.hasQuery ||
        apiBase.hasFragment) {
      throw ArgumentError('应用环境或 API 地址无效');
    }
    if (apiBase.scheme != 'https' &&
        !(environment == 'local' && debug && apiBase.scheme == 'http')) {
      throw ArgumentError('此构建只允许 HTTPS API');
    }
  }
  final String environment;
  final AppBranding branding;
  bool get isOffline => branding == AppBranding.jiyi;
  final Uri apiBase;
  // iOS production uses Apple-backed acquisition. Default to enabled so an
  // Xcode archive cannot accidentally expose the redemption-only fallback.
  static const iosAcquisitionEnabled = bool.fromEnvironment(
    'IOS_ACQUISITION_ENABLED',
    defaultValue: true,
  );
  static AppConfig fromBuild() {
    const endpoint = String.fromEnvironment(
      'API_BASE_URL',
      defaultValue: 'https://api.invalid/api/v1',
    );
    return AppConfig(
      environment: appFlavor ?? 'prod',
      apiBase: Uri.parse(endpoint),
      debug: kDebugMode,
    );
  }
}
