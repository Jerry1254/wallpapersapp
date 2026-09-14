import 'dart:convert';
import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/config/app_config.dart';
import 'package:qingjing_wallpaper/config/internal_tls.dart';

void main() {
  test('内测 HTTPS 验证公共证书和主机名，其他环境不添加内测信任', () async {
    final temporary = await Directory.systemTemp.createTemp('qj-internal-tls-');
    HttpServer? server;
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 5);
    try {
      final certificate = File('${temporary.path}/cert.pem');
      final key = File('${temporary.path}/key.pem');
      final configFile = File('${temporary.path}/tls.cnf');
      await configFile.writeAsString('''
[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=localhost
[ext]
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost
''');
      final generated = await Process.run('openssl', [
        'req',
        '-x509',
        '-newkey',
        'rsa:2048',
        '-nodes',
        '-sha256',
        '-days',
        '1',
        '-config',
        configFile.path,
        '-keyout',
        key.path,
        '-out',
        certificate.path,
      ]);
      expect(generated.exitCode, 0);
      final context = SecurityContext()
        ..useCertificateChain(certificate.path)
        ..usePrivateKey(key.path);
      server = await HttpServer.bindSecure(
        InternetAddress.loopbackIPv4,
        0,
        context,
      );
      server.listen((request) async {
        request.response.write('verified');
        await request.response.close();
      }, onError: (_) {});
      final uri = Uri.parse('https://localhost:${server.port}/');
      final encoded = base64Encode(await certificate.readAsBytes());
      for (final environment in ['prod', 'local']) {
        configureInternalTls(
          AppConfig(environment: environment, apiBase: uri, debug: false),
          publicCertificate: encoded,
        );
      }
      await expectLater(client.getUrl(uri), throwsA(isA<HandshakeException>()));
      final config = AppConfig(
        environment: 'internal',
        apiBase: uri,
        debug: false,
      );
      expect(
        () => configureInternalTls(config, publicCertificate: ''),
        throwsArgumentError,
      );
      configureInternalTls(config, publicCertificate: encoded);
      final trusted = HttpClient()
        ..connectionTimeout = const Duration(seconds: 5);
      try {
        final request = await trusted.getUrl(uri);
        final response = await request.close();
        expect(await utf8.decoder.bind(response).join(), 'verified');
        await expectLater(
          trusted.getUrl(uri.replace(host: '127.0.0.1')),
          throwsA(isA<HandshakeException>()),
        );
      } finally {
        trusted.close(force: true);
      }
      expect(
        () => AppConfig(
          environment: 'internal',
          apiBase: Uri.parse('https://example.com'),
          debug: false,
        ),
        throwsArgumentError,
      );
      expect(
        () => AppConfig(
          environment: 'internal',
          apiBase: uri.replace(scheme: 'http'),
          debug: false,
        ),
        throwsArgumentError,
      );
    } finally {
      client.close(force: true);
      await server?.close(force: true);
      await temporary.delete(recursive: true);
    }
  });
}
