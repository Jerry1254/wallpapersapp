import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:qingjing_customer_support/customer_support.dart';

class AgentSession {
  AgentSession(this.base, {this.storage = const FlutterSecureStorage()});
  final Uri base;
  final FlutterSecureStorage storage;
  String cookie = '', csrf = '', adminId = '', username = '';
  void Function()? onExpired;
  String get key => 'qingjing-agent-session:${base.toString()}';
  Future<bool> restore() async {
    cookie = await storage.read(key: key) ?? '';
    if (cookie.isEmpty) return false;
    try {
      apply(await request('/admin/sessions', 'GET', null));
      return true;
    } on SupportFailure catch (error) {
      if (error.status == 401) {
        await clear();
        return false;
      }
      rethrow;
    }
  }

  void apply(SupportData data) {
    final admin = data['admin'] as Map;
    csrf = data['csrfToken'] as String;
    adminId = admin['id'] as String;
    username = admin['username'] as String;
  }

  Future<void> login(String name, String password) async {
    if (base.host == 'api.invalid') {
      throw const SupportFailure(
        0,
        'NOT_CONFIGURED',
        '此安装包尚未配置服务地址，请使用已配置的客服安装包',
      );
    }
    final data = await request('/admin/sessions', 'POST', {
      'username': name,
      'password': password,
    });
    apply(data);
    if (cookie.isEmpty) {
      throw const SupportFailure(0, 'INVALID_SESSION', '登录未完成，请重试');
    }
    await storage.write(key: key, value: cookie);
  }

  Future<void> clear() async {
    cookie = '';
    csrf = '';
    adminId = '';
    await storage.delete(key: key);
  }

  Future<void> logout() async {
    try {
      if (cookie.isNotEmpty) await request('/admin/sessions', 'DELETE', null);
    } finally {
      await clear();
    }
  }

  Future<Map<String, String>> headers() async => {
    'Cookie': cookie,
    'X-CSRF-Token': csrf,
    'X-Request-Id': supportUuid(),
  };
  EndpointSupportApi api() => EndpointSupportApi(
    agent: true,
    base: base,
    request: request,
    headers: headers,
    scope: () async => '${base.toString()}:admin:$adminId',
  );
  Future<SupportData> request(
    String path,
    String method,
    SupportData? body,
  ) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      return await (() async {
        final req = await client.openUrl(
          method,
          base.resolve('${base.path}$path'),
        );
        req.followRedirects = false;
        req.headers.set('Accept', 'application/json');
        if (cookie.isNotEmpty) req.headers.set('Cookie', cookie);
        if (method != 'GET') {
          req.headers.set('X-Request-Id', supportUuid());
          if (csrf.isNotEmpty) req.headers.set('X-CSRF-Token', csrf);
        }
        if (body != null) {
          req.headers.contentType = ContentType.json;
          req.add(utf8.encode(jsonEncode(body)));
        }
        final response = await req.close();
        final text = await utf8.decoder.bind(response).join();
        if (response.statusCode == 401 && path != '/admin/sessions') {
          await clear();
          onExpired?.call();
        }
        if (response.statusCode == 401 &&
            path == '/admin/sessions' &&
            method == 'POST') {
          throw const SupportFailure(401, 'UNAUTHORIZED', '账号或密码不正确，请重试');
        }
        final data = decodeSupportResponse(response.statusCode, text);
        for (final value in response.cookies) {
          if (value.name == 'QJ_ADMIN_SESSION') {
            cookie = '${value.name}=${value.value}';
          }
        }
        return data;
      })().timeout(const Duration(seconds: 15));
    } on SupportFailure {
      rethrow;
    } catch (_) {
      throw const SupportFailure(0, 'NETWORK_ERROR', '网络连接失败，请核对已提交的消息后重试');
    } finally {
      client.close(force: true);
    }
  }
}
