import 'dart:convert';
import 'dart:io';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_customer_support/customer_support.dart';
import 'package:support_agent/session.dart';

void main() {
  test('后台登录 Cookie 加密保存，恢复时重新获取 CSRF，写请求携带校验且登出清除', () async {
    FlutterSecureStorage.setMockInitialValues({});
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    var postCount = 0;
    server.listen((request) async {
      request.response.headers.contentType = ContentType.json;
      if (request.uri.path.endsWith('/sessions')) {
        if (request.method == 'POST') {
          final body =
              jsonDecode(await utf8.decoder.bind(request).join()) as Map;
          expect(body['username'], '客服');
          request.response.cookies.add(
            Cookie('QJ_ADMIN_SESSION', 'test-only-session')..httpOnly = true,
          );
          postCount++;
        } else {
          expect(
            request.headers.value('cookie'),
            'QJ_ADMIN_SESSION=test-only-session',
          );
        }
        if (request.method == 'DELETE') {
          expect(request.headers.value('x-csrf-token'), 'test-csrf');
          request.response.statusCode = 204;
        } else {
          request.response.write(
            jsonEncode({
              'admin': {'id': '1', 'username': '客服'},
              'csrfToken': 'test-csrf',
              'expiresAt': '2099-01-01T00:00:00Z',
            }),
          );
        }
      } else {
        expect(
          request.headers.value('cookie'),
          'QJ_ADMIN_SESSION=test-only-session',
        );
        expect(request.headers.value('x-csrf-token'), 'test-csrf');
        expect(request.headers.value('x-request-id'), isNotEmpty);
        request.response.write('{}');
      }
      await request.response.close();
    });
    try {
      final base = Uri.parse('http://127.0.0.1:${server.port}/api/v1');
      final first = AgentSession(base);
      await first.login('客服', 'test-only-password');
      expect(
        await first.storage.read(key: first.key),
        'QJ_ADMIN_SESSION=test-only-session',
      );
      final restored = AgentSession(base);
      expect(await restored.restore(), true);
      expect(restored.adminId, '1');
      await restored.request('/admin/support/conversations/1/read', 'POST', {
        'messageId': '1',
      });
      expect(postCount, 1);
      await restored.logout();
      expect(await restored.storage.read(key: restored.key), null);
      expect(restored.cookie, isEmpty);
    } finally {
      await server.close(force: true);
    }
  });
  test('管理员会话失效停止使用旧 Cookie，并通知返回登录', () async {
    FlutterSecureStorage.setMockInitialValues({});
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      request.response.statusCode = 401;
      request.response.headers.contentType = ContentType.json;
      request.response.write('{"error":{"code":"SESSION_EXPIRED"}}');
      await request.response.close();
    });
    try {
      final session = AgentSession(
        Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      );
      session.cookie = 'QJ_ADMIN_SESSION=test-only-session';
      session.csrf = 'stale';
      var expired = 0;
      session.onExpired = () => expired++;
      await expectLater(
        session.request('/admin/support/conversations', 'GET', null),
        throwsA(isA<SupportFailure>()),
      );
      expect(expired, 1);
      expect(session.cookie, isEmpty);
      expect(session.csrf, isEmpty);
    } finally {
      await server.close(force: true);
    }
  });
}
