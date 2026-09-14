import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class AppConfig {
  AppConfig({
    required this.environment,
    required this.apiBase,
    required bool debug,
  }) {
    if (!{'local', 'internal', 'prod'}.contains(environment) ||
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
    if (environment == 'internal' &&
        !{'127.0.0.1', 'localhost'}.contains(apiBase.host)) {
      throw ArgumentError('本地候选内测只允许 loopback API');
    }
  }
  final String environment;
  final Uri apiBase;
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
