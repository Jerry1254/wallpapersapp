import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import '../device/device_capabilities.dart';
import '../device/device_session.dart';

class ApiFailure implements Exception {
  const ApiFailure(this.status, this.code);
  final int status;
  final String code;
  String get message => switch ((status, code)) {
    (404, 'WALLPAPER_NOT_AVAILABLE_FOR_DEVICE') => '此内容已不适用于当前手机',
    (404, _) => '内容不存在或已经下线',
    (428, _) => '正在重新检测手机的壁纸设置能力',
    (400, _) => '查询参数无效，请调整后重试',
    _ => '暂时无法加载，请检查网络后重试',
  };
}

String _id(dynamic value) {
  if (value is! String || !RegExp(r'^[1-9][0-9]*$').hasMatch(value)) {
    throw const FormatException('Invalid ID');
  }
  return value;
}

class Category {
  Category.fromJson(Map<String, dynamic> json)
    : id = _id(json['id']),
      name = json['name'] as String,
      icon = (json['icon'] as Map<String, dynamic>?)?['contentUrl'] as String?,
      children = ((json['children'] as List?) ?? [])
          .map((e) => Category.fromJson(e as Map<String, dynamic>))
          .toList();
  final String id, name;
  final String? icon;
  final List<Category> children;
}

class Wallpaper {
  Wallpaper.fromJson(Map<String, dynamic> json)
    : id = _id(json['id']),
      title = json['title'] as String,
      accessType = json['accessType'] == 'FREE' ? 'FREE' : 'REDEEM',
      cover = (json['cover'] as Map<String, dynamic>)['contentUrl'] as String,
      availableCapabilities = ((json['availableCapabilities'] as List?) ?? [])
          .map(
            (item) => AvailableCapability.fromJson(
              Map<String, dynamic>.from(item as Map),
            ),
          )
          .toList(growable: false),
      copyright = json['copyrightNote'] as String?;
  final String id, title, accessType, cover;
  final List<AvailableCapability> availableCapabilities;
  final String? copyright;
  bool get isFree => accessType == 'FREE';
  List<String> get capabilityLabels {
    final labels = availableCapabilities.map((item) => item.label).toSet();
    const order = ['4D动态', '动态', '静态'];
    return order.where(labels.contains).toList(growable: false);
  }

  String get kindLabel =>
      capabilityLabels.isEmpty ? '暂不可用' : capabilityLabels.join(' · ');
  bool hasCapability(String platform, String type) => availableCapabilities.any(
    (item) => item.deliveryPlatform == platform && item.resourceType == type,
  );
}

class AvailableCapability {
  const AvailableCapability({
    required this.deliveryPlatform,
    required this.resourceType,
    required this.placements,
  });
  factory AvailableCapability.fromJson(Map<String, dynamic> json) =>
      AvailableCapability(
        deliveryPlatform: json['deliveryPlatform'] as String,
        resourceType: json['resourceType'] as String,
        placements: Set<String>.from(json['placements'] as List),
      );
  final String deliveryPlatform, resourceType;
  final Set<String> placements;
  String get label => switch (resourceType) {
    'LAYER_PARALLAX' => '4D动态',
    'VIDEO' || 'LIVE_PHOTO' || 'THEME_PACKAGE' => '动态',
    'STATIC_IMAGE' => '静态',
    _ => '未知形式',
  };
}

class WallpaperPage {
  WallpaperPage(this.items, this.page, this.totalPages);
  WallpaperPage.fromJson(Map<String, dynamic> json)
    : items = (json['items'] as List)
          .map((e) => Wallpaper.fromJson(e as Map<String, dynamic>))
          .toList(),
      page = (json['page'] as Map<String, dynamic>)['page'] as int,
      totalPages = (json['page'] as Map<String, dynamic>)['totalPages'] as int;
  final List<Wallpaper> items;
  final int page, totalPages;
}

class WallpaperTutorial {
  WallpaperTutorial.fromJson(Map<String, dynamic> json)
    : key = json['key'] as String,
      title = json['title'] as String,
      platform = json['platform'] as String,
      wallpaperKind = json['wallpaperKind'] as String,
      videoPath =
          (json['video'] as Map<String, dynamic>)['contentUrl'] as String,
      videoMimeType =
          (json['video'] as Map<String, dynamic>)['mimeType'] as String,
      durationMs = (json['video'] as Map<String, dynamic>)['durationMs'] as int,
      sortOrder = json['sortOrder'] as int;
  final String key, title, platform, wallpaperKind, videoPath, videoMimeType;
  final int durationMs, sortOrder;
}

String? tutorialKeyFor(String platform, String wallpaperKind) {
  if (wallpaperKind == 'STATIC') return 'STATIC';
  if (platform == 'ANDROID' && wallpaperKind == 'PARALLAX_4D') {
    return 'ANDROID_PARALLAX_4D';
  }
  if (platform == 'ANDROID' && wallpaperKind == 'DYNAMIC') {
    return 'ANDROID_DYNAMIC';
  }
  if (platform == 'HARMONYOS' && wallpaperKind == 'DYNAMIC') {
    return 'HARMONYOS_DYNAMIC';
  }
  if (platform == 'IOS' && wallpaperKind == 'DYNAMIC') {
    return 'IOS_DYNAMIC';
  }
  return null;
}

String? tutorialKeyForCapability(String platform, String resourceType) =>
    switch ((platform, resourceType)) {
      ('ANDROID', 'LAYER_PARALLAX') => 'ANDROID_PARALLAX_4D',
      ('ANDROID', 'VIDEO') => 'ANDROID_DYNAMIC',
      ('HARMONYOS', 'THEME_PACKAGE') => 'HARMONYOS_DYNAMIC',
      ('IOS', 'LIVE_PHOTO') => 'IOS_DYNAMIC',
      ('UNIVERSAL', 'STATIC_IMAGE') => 'STATIC',
      _ => null,
    };

WallpaperTutorial? tutorialForCapability(
  List<WallpaperTutorial> tutorials,
  AvailableCapability capability,
) {
  final key = tutorialKeyForCapability(
    capability.deliveryPlatform,
    capability.resourceType,
  );
  if (key == null) return null;
  return tutorials.where((item) => item.key == key).firstOrNull;
}

WallpaperTutorial? tutorialFor(
  List<WallpaperTutorial> tutorials,
  String platform,
  String wallpaperKind,
) {
  final key = tutorialKeyFor(platform, wallpaperKind);
  if (key == null) return null;
  return tutorials.where((item) => item.key == key).firstOrNull;
}

abstract interface class CatalogRepository {
  Future<List<Category>> categories();
  Future<WallpaperPage> list(Map<String, String> query);
  Future<Wallpaper> detail(String id);
  Future<List<WallpaperTutorial>> tutorials();
  Uri media(String path);
}

class HttpCatalogRepository implements CatalogRepository {
  HttpCatalogRepository(this.base, {this.sessions, this.deviceCapabilities});
  final Uri base;
  final DeviceSessionManager? sessions;
  final DeviceCapabilityManager? deviceCapabilities;
  Future<Map<String, dynamic>> _get(
    String path, [
    Map<String, String>? query,
    bool authenticated = true,
  ]) async {
    if (authenticated && sessions != null) {
      final uri = Uri(path: path, queryParameters: query);
      Future<Map<String, dynamic>> request() =>
          sessions!.authenticated(uri.toString());
      try {
        return deviceCapabilities == null
            ? await request()
            : await deviceCapabilities!.withProfile(request);
      } on DeviceApiError catch (error) {
        throw ApiFailure(error.status, error.code);
      } catch (_) {
        throw const ApiFailure(0, 'NETWORK_OR_RESPONSE_ERROR');
      }
    }
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      return await (() async {
        final uri = base.replace(
          path: '${base.path}$path',
          queryParameters: query,
        );
        final request = await client.getUrl(uri);
        request.followRedirects = false;
        request.headers.set(HttpHeaders.acceptHeader, 'application/json');
        final response = await request.close();
        final bytes = <int>[];
        await for (final chunk in response) {
          bytes.addAll(chunk);
          if (bytes.length > 4 * 1024 * 1024) {
            throw const FormatException('Response too large');
          }
        }
        if (response.statusCode < 200 || response.statusCode >= 300) {
          throw ApiFailure(response.statusCode, 'REQUEST_FAILED');
        }
        return jsonDecode(utf8.decode(bytes)) as Map<String, dynamic>;
      })().timeout(const Duration(seconds: 15));
    } on ApiFailure {
      rethrow;
    } catch (_) {
      throw const ApiFailure(0, 'NETWORK_OR_RESPONSE_ERROR');
    } finally {
      client.close(force: true);
    }
  }

  @override
  Future<List<Category>> categories() async =>
      ((await _get('/public/categories'))['items'] as List)
          .map((e) => Category.fromJson(e as Map<String, dynamic>))
          .toList();
  @override
  Future<WallpaperPage> list(Map<String, String> query) async =>
      WallpaperPage.fromJson(await _get('/public/wallpapers', query));
  @override
  Future<Wallpaper> detail(String id) async => Wallpaper.fromJson(
    await _get('/public/wallpapers/${Uri.encodeComponent(_id(id))}'),
  );
  @override
  Future<List<WallpaperTutorial>> tutorials() async =>
      ((await _get('/public/wallpaper-tutorials', null, false))['items']
              as List)
          .map((e) => WallpaperTutorial.fromJson(e as Map<String, dynamic>))
          .toList()
        ..sort((left, right) => left.sortOrder.compareTo(right.sortOrder));
  @override
  Uri media(String path) {
    final uri = base.resolve(path);
    if (!{'http', 'https'}.contains(uri.scheme) ||
        uri.userInfo.isNotEmpty ||
        (base.scheme == 'https' && uri.scheme != 'https')) {
      throw const FormatException('Invalid media URL');
    }
    return uri;
  }
}

/// Keeps prior results on failures and ignores obsolete query responses.
class CatalogController extends ChangeNotifier {
  CatalogController(this.repository);
  final CatalogRepository repository;
  List<Wallpaper> items = [];
  Map<String, String> query = {};
  bool loading = false, loadingMore = false, hasMore = false;
  String? error;
  bool _retryMore = false;
  Future<void> retry() => _retryMore ? more() : load(query);
  int _generation = 0, _page = 0;
  bool _disposed = false;
  Future<void> load(Map<String, String> next) async {
    final generation = ++_generation;
    final same = mapEquals(query, next);
    query = Map.of(next);
    if (!same) {
      items = [];
      _page = 0;
      hasMore = false;
    }
    _retryMore = false;
    loading = true;
    loadingMore = false;
    error = null;
    notifyListeners();
    try {
      final page = await repository.list({
        ...query,
        'page': '1',
        'pageSize': '20',
      });
      if (_disposed || generation != _generation) return;
      items = page.items;
      _page = page.page;
      hasMore = _page < page.totalPages;
    } catch (e) {
      if (_disposed || generation != _generation) return;
      error = e is ApiFailure ? e.message : '加载失败，请重试';
    } finally {
      if (!_disposed && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  Future<void> more() async {
    if (loading || loadingMore || !hasMore || _disposed) return;
    final generation = _generation;
    _retryMore = true;
    loadingMore = true;
    error = null;
    notifyListeners();
    try {
      final page = await repository.list({
        ...query,
        'page': '${_page + 1}',
        'pageSize': '20',
      });
      if (_disposed || generation != _generation) return;
      final merged = {for (final item in items) item.id: item};
      for (final item in page.items) {
        merged[item.id] = item;
      }
      items = merged.values.toList();
      _page = page.page;
      hasMore = _page < page.totalPages;
    } catch (e) {
      if (_disposed || generation != _generation) return;
      error = e is ApiFailure ? e.message : '加载失败，请重试';
    } finally {
      if (!_disposed && generation == _generation) {
        loadingMore = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    super.dispose();
  }
}
