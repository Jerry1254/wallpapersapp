import 'dart:convert';
import 'dart:io';
import 'app_config.dart';

// Android's native URLConnection uses the flavor's network security config.
// Dart clients need their own public trust anchor; normal TLS checks remain active.
void configureInternalTls(AppConfig config, {String? publicCertificate}) {
  if (config.environment != 'internal') return;
  const fromBuild = String.fromEnvironment('INTERNAL_TLS_CERTIFICATE');
  final encoded = publicCertificate ?? fromBuild;
  if (encoded.isEmpty || encoded.length > 24000) {
    throw ArgumentError('内测 HTTPS 公共证书未配置');
  }
  final bytes = base64Decode(encoded);
  final pem = utf8.decode(bytes);
  if (!pem.contains('-----BEGIN CERTIFICATE-----') ||
      pem.contains('PRIVATE KEY')) {
    throw ArgumentError('内测配置必须只包含公共证书');
  }
  SecurityContext.defaultContext.setTrustedCertificatesBytes(bytes);
}
