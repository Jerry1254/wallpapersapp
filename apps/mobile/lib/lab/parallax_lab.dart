import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';
import '../device/device_session.dart';

class ParallaxLabConfig {
  ParallaxLabConfig._(this.value);
  factory ParallaxLabConfig.decode(String source) {
    if (utf8.encode(source).length > 65536) throw const FormatException();
    final value = jsonDecode(source);
    if (value is! Map<String, dynamic> ||
        value['formatVersion'] != 2 ||
        value['canvas'] is! Map ||
        value['motion'] is! Map ||
        value['layers'] is! List) {
      throw const FormatException();
    }
    final motion = Map<String, dynamic>.from(value['motion'] as Map);
    _range(motion['maxAngleX'], 1, 75);
    _range(motion['maxAngleY'], 1, 75);
    final layers = (value['layers'] as List)
        .map((item) => Map<String, dynamic>.from(item as Map))
        .toList();
    if (layers.length < 2 || layers.length > 12) throw const FormatException();
    for (var index = 0; index < layers.length; index++) {
      final layer = layers[index];
      if (layer['index'] != index + 1 ||
          !{'follow', 'reverse', 'fixed'}.contains(layer['direction']) ||
          !{'normal', 'screen', 'add'}.contains(layer['blendMode'])) {
        throw const FormatException();
      }
      _range(layer['offsetXPercent'], 0, double.maxFinite);
      _range(layer['offsetYPercent'], 0, double.maxFinite);
      _range(
        layer['initialOffsetXPercent'],
        -double.maxFinite,
        double.maxFinite,
      );
      _range(
        layer['initialOffsetYPercent'],
        -double.maxFinite,
        double.maxFinite,
      );
      _range(layer['scale'], 1, 1.5);
      _range(layer['opacity'], 0, 1);
    }
    value['motion'] = motion;
    value['layers'] = layers;
    return ParallaxLabConfig._(value);
  }
  final Map<String, dynamic> value;
  Map<String, dynamic> get motion => value['motion'] as Map<String, dynamic>;
  List<Map<String, dynamic>> get layers =>
      List<Map<String, dynamic>>.from(value['layers'] as List);
  String get encoded => jsonEncode(value);
  ParallaxLabConfig copy() => ParallaxLabConfig.decode(encoded);
  static double number(dynamic value) => (value as num).toDouble();
  static void _range(dynamic value, double minimum, double maximum) {
    if (value is! num ||
        !value.toDouble().isFinite ||
        value < minimum ||
        value > maximum) {
      throw const FormatException();
    }
  }
}

class ParallaxLabApiError implements Exception {
  const ParallaxLabApiError(this.status, this.code, this.message);
  final int status;
  final String code, message;
}

class ParallaxLabAdminClient {
  ParallaxLabAdminClient(this.base);
  final Uri base;
  String? _cookie, _csrf;
  bool get authenticated => _cookie != null && _csrf != null;

  Future<void> login(String username, String password) async {
    final response = await _request(
      '/admin/sessions',
      method: 'POST',
      body: {'username': username, 'password': password},
      includeSession: false,
    );
    final sessionCookie = response.cookies
        .where((item) => item.name == 'QJ_ADMIN_SESSION')
        .firstOrNull;
    final data = response.data;
    if (sessionCookie == null || data['csrfToken'] is! String) {
      throw const ParallaxLabApiError(0, 'INVALID_RESPONSE', '管理员登录结果无效');
    }
    _cookie = '${sessionCookie.name}=${sessionCookie.value}';
    _csrf = data['csrfToken'] as String;
  }

  Future<Map<String, dynamic>> save(
    String wallpaperId,
    String baseResourceVersionId,
    Map<String, dynamic> config,
  ) async {
    if (!authenticated) {
      throw const ParallaxLabApiError(401, 'SESSION_EXPIRED', '管理员登录后才能保存配置');
    }
    return (await _request(
      '/admin/lab/wallpapers/${Uri.encodeComponent(wallpaperId)}/parallax-config',
      method: 'POST',
      body: {'baseResourceVersionId': baseResourceVersionId, 'config': config},
    )).data;
  }

  Future<_LabResponse> _request(
    String path, {
    required String method,
    required Map<String, dynamic> body,
    bool includeSession = true,
  }) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      final uri = base.resolve('${base.path}$path');
      final request = await client.openUrl(method, uri);
      request.followRedirects = false;
      request.headers.contentType = ContentType.json;
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      request.headers.set('X-Request-Id', requestUuid());
      if (includeSession) {
        request.headers.set(HttpHeaders.cookieHeader, _cookie!);
        request.headers.set('X-CSRF-Token', _csrf!);
      }
      request.add(utf8.encode(jsonEncode(body)));
      final response = await request.close().timeout(
        const Duration(seconds: 15),
      );
      final bytes = <int>[];
      await for (final chunk in response) {
        bytes.addAll(chunk);
        if (bytes.length > 4 * 1024 * 1024) throw const FormatException();
      }
      final value = jsonDecode(utf8.decode(bytes)) as Map<String, dynamic>;
      if (response.statusCode < 200 || response.statusCode >= 300) {
        final error = value['error'] as Map<String, dynamic>?;
        if (response.statusCode == 401) {
          _cookie = null;
          _csrf = null;
        }
        throw ParallaxLabApiError(
          response.statusCode,
          error?['code'] as String? ?? 'REQUEST_FAILED',
          error?['message'] as String? ?? '保存失败',
        );
      }
      return _LabResponse(value, response.cookies);
    } on ParallaxLabApiError {
      rethrow;
    } catch (_) {
      throw const ParallaxLabApiError(0, 'NETWORK_ERROR', '网络不可用，本次修改仍已保留');
    } finally {
      client.close(force: true);
    }
  }
}

class _LabResponse {
  const _LabResponse(this.data, this.cookies);
  final Map<String, dynamic> data;
  final List<Cookie> cookies;
}

class ParallaxLabNative {
  static const _channel = MethodChannel('qingjing/wallpaper_android');
  Future<Map<String, dynamic>?> readDraft(String wallpaperId) async {
    final source = await _channel.invokeMethod<String>('readParallaxLabDraft', {
      'wallpaperId': wallpaperId,
    });
    if (source == null) return null;
    return Map<String, dynamic>.from(jsonDecode(source) as Map);
  }

  Future<void> writeDraft(
    String wallpaperId,
    String baseResourceVersionId,
    String config,
  ) => _channel.invokeMethod<void>('writeParallaxLabDraft', {
    'wallpaperId': wallpaperId,
    'baseResourceVersionId': baseResourceVersionId,
    'config': config,
  });

  Future<void> clearDraft(String wallpaperId) => _channel.invokeMethod<void>(
    'clearParallaxLabDraft',
    {'wallpaperId': wallpaperId},
  );
}
