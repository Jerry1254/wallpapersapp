import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../device/device_session.dart';

int compareMarketingVersions(String left, String right) {
  final a = left.split('.').map(BigInt.parse).toList();
  final b = right.split('.').map(BigInt.parse).toList();
  for (var i = 0; i < 3; i++) {
    final difference = (i < a.length ? a[i] : BigInt.zero).compareTo(
      i < b.length ? b[i] : BigInt.zero,
    );
    if (difference != 0) return difference;
  }
  return 0;
}

class InstalledAppVersion {
  const InstalledAppVersion(
    this.platform,
    this.name,
    this.code, {
    this.androidSdk,
    this.abi,
    this.packageName,
  });
  final String platform, name;
  final int code;
  final int? androidSdk;
  final String? abi;
  final String? packageName;
  Map<String, String> get headers => {
    'X-App-Version-Name': name,
    'X-App-Version-Code': '$code',
    if (androidSdk != null) 'X-App-Android-Sdk': '$androidSdk',
    'X-App-ABI': ?abi,
  };
  Map<String, String> get query => {
    'platform': platform,
    'versionName': name,
    'versionCode': '$code',
    if (androidSdk != null) 'androidSdk': '$androidSdk',
    'abi': ?abi,
    'packageName': ?packageName,
  };
  bool isBefore(AppRelease release) => platform == 'ios'
      ? compareMarketingVersions(name, release.name) < 0
      : code < release.code;
}

class AppRelease {
  AppRelease(this.json);
  final Map<String, dynamic> json;
  String get id => json['id'].toString();
  String get name => json['versionName'] as String;
  int get code => (json['versionCode'] as num).toInt();
  String get notes => json['releaseNotes'] as String? ?? '';
  String? get downloadUrl => json['downloadUrl'] as String?;
  String? get storeUrl => json['storeUrl'] as String?;
  String get hash => (json['sha256'] as String).toLowerCase();
  int get size => (json['fileSize'] as num).toInt();
}

class AppUpdatePolicy {
  const AppUpdatePolicy({
    required this.mandatory,
    required this.available,
    this.minimum,
    this.latest,
  });
  factory AppUpdatePolicy.fromJson(
    Map<String, dynamic> value,
  ) => AppUpdatePolicy(
    mandatory: value['mandatory'] == true,
    available: value['updateAvailable'] == true,
    minimum: value['minimumVersion'] is Map
        ? AppRelease(Map<String, dynamic>.from(value['minimumVersion'] as Map))
        : null,
    latest: value['latestVersion'] is Map
        ? AppRelease(Map<String, dynamic>.from(value['latestVersion'] as Map))
        : null,
  );
  final bool mandatory, available;
  final AppRelease? minimum, latest;
  Map<String, dynamic> toJson() => {
    'mandatory': mandatory,
    'updateAvailable': available,
    'minimumVersion': minimum?.json,
    'latestVersion': latest?.json,
  };
}

abstract interface class AppUpdatePlatform {
  Future<InstalledAppVersion> installed();
  Future<String?> cached(String scope);
  Future<void> save(String scope, String? value);
  Future<String> downloadPath(String key);
  Future<String> install(String path, AppRelease release);
  Future<void> openStore(String url);
}

class NativeAppUpdatePlatform implements AppUpdatePlatform {
  const NativeAppUpdatePlatform();
  static const _channel = MethodChannel('qingjing/app_updates');
  @override
  Future<InstalledAppVersion> installed() async {
    final value = (await _channel.invokeMapMethod<String, dynamic>(
      'installedVersion',
    ))!;
    return InstalledAppVersion(
      Platform.isIOS ? 'ios' : 'android',
      value['versionName'] as String,
      (value['versionCode'] as num).toInt(),
      androidSdk: value['androidSdk'] as int?,
      abi: value['abi'] as String?,
      packageName: value['packageName'] as String?,
    );
  }

  @override
  Future<String?> cached(String scope) =>
      _channel.invokeMethod<String>('cachedRequirement', scope);
  @override
  Future<void> save(String scope, String? value) => _channel.invokeMethod<void>(
    'saveRequirement',
    {'scope': scope, 'value': value},
  );
  @override
  Future<String> downloadPath(String key) async =>
      (await _channel.invokeMethod<String>('downloadPath', key))!;
  @override
  Future<String> install(String path, AppRelease release) async =>
      (await _channel.invokeMethod<String>('installApk', {
        'path': path,
        'versionCode': release.code,
        'sha256': release.hash,
        'fileSize': release.size,
      }))!;
  @override
  Future<void> openStore(String url) =>
      _channel.invokeMethod<void>('openStore', url);
}

abstract interface class AppUpdateApi {
  Future<AppUpdatePolicy> check(InstalledAppVersion version);
}

class HttpAppUpdateApi implements AppUpdateApi {
  HttpAppUpdateApi(Uri base) : transport = HttpDeviceTransport(base);
  final DeviceTransport transport;
  @override
  Future<AppUpdatePolicy> check(InstalledAppVersion version) async {
    final query = Uri(queryParameters: version.query).query;
    final envelope = await transport.request('/app-updates/check?$query');
    final data = Map<String, dynamic>.from(envelope['data'] as Map);
    if (data['platform'] != version.platform ||
        data['mandatory'] is! bool ||
        data['updateAvailable'] is! bool ||
        (data['mandatory'] == true &&
            (data['minimumVersion'] is! Map ||
                data['latestVersion'] is! Map)) ||
        (data['updateAvailable'] == true && data['latestVersion'] is! Map)) {
      throw const FormatException('Invalid update decision');
    }
    return AppUpdatePolicy.fromJson(data);
  }
}

class AppUpdateController extends ChangeNotifier {
  AppUpdateController(
    this.base, {
    AppUpdatePlatform? platform,
    AppUpdateApi? api,
  }) : platform = platform ?? const NativeAppUpdatePlatform(),
       api = api ?? HttpAppUpdateApi(base);
  final Uri base;
  final AppUpdatePlatform platform;
  final AppUpdateApi api;
  InstalledAppVersion? version;
  AppUpdatePolicy? policy;
  bool ready = false, checking = false, updating = false;
  String? message;
  double? progress;
  String? _dismissed;
  Future<void>? _pending;
  bool _disposed = false, _cacheLoaded = false;
  int _restrictionGeneration = 0;
  bool _recheckAfterCurrent = false;
  String get _scope => base.toString();
  bool get required => policy?.mandatory == true;
  bool get visible =>
      required ||
      (policy?.available == true && policy?.latest?.id != _dismissed);

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  Future<Map<String, String>> versionHeaders() async {
    try {
      version ??= await platform.installed();
      return version!.headers;
    } catch (_) {
      return {};
    }
  }

  Future<void> start() => check();
  Future<void> check() {
    if (_pending != null) return _pending!;
    final operation = _check();
    _pending = operation;
    return operation.whenComplete(() {
      if (identical(_pending, operation)) _pending = null;
      if (_recheckAfterCurrent && !_disposed) {
        _recheckAfterCurrent = false;
        unawaited(check());
      }
    });
  }

  Future<void> _check() async {
    final generation = _restrictionGeneration;
    checking = true;
    _changed();
    try {
      version = await platform.installed();
      if (!_cacheLoaded) {
        _cacheLoaded = true;
        try {
          final saved = await platform.cached(_scope);
          if (saved != null) {
            final cache = AppUpdatePolicy.fromJson(
              jsonDecode(saved) as Map<String, dynamic>,
            );
            if (cache.mandatory &&
                (cache.minimum == null || version!.isBefore(cache.minimum!))) {
              policy = cache;
              _changed();
            }
          }
        } catch (_) {
          /* A failed read is not proof of a mandatory update. */
        }
      }
      final result = await api.check(version!);
      if (generation != _restrictionGeneration) {
        _recheckAfterCurrent = true;
        return;
      }
      policy = result;
      message = null;
      // Only a successful API decision can revoke a previously confirmed gate.
      await platform.save(
        _scope,
        result.mandatory ? jsonEncode(result.toJson()) : null,
      );
    } catch (_) {
      message = '更新检查失败，请重试';
    } finally {
      checking = false;
      ready = true;
      _changed();
    }
  }

  void requireFromServer() {
    _restrictionGeneration++;
    final previousMinimum = policy?.minimum;
    policy = AppUpdatePolicy(
      mandatory: true,
      available: true,
      // A new server rejection may refer to a newly raised threshold. An old
      // already-satisfied minimum must not make a restart discard this proof.
      minimum:
          previousMinimum != null && version?.isBefore(previousMinimum) == true
          ? previousMinimum
          : null,
      latest: null,
    );
    _changed();
    unawaited(
      platform.save(_scope, jsonEncode(policy!.toJson())).catchError((_) {}),
    );
    if (checking) {
      _recheckAfterCurrent = true;
    } else {
      unawaited(check());
    }
  }

  void dismiss() {
    if (required || updating) return;
    _dismissed = policy?.latest?.id;
    _changed();
  }

  Future<void> update() async {
    if (updating || policy?.latest == null) return;
    final target = policy!.latest!;
    updating = true;
    message = null;
    progress = null;
    _changed();
    try {
      if (version?.platform == 'android') {
        final path = await _downloadApk(target);
        message = '正在打开系统安装，请确认覆盖安装';
        _changed();
        final result = await platform.install(path, target);
        message = result == 'permissionRequested'
            ? '请允许此 App 安装应用，返回后继续安装'
            : '请完成系统安装；取消后可点击重新安装';
      } else {
        final url = target.storeUrl;
        if (url == null) throw const FormatException('Missing store URL');
        await platform.openStore(url);
        message = '请在应用商店完成更新，返回后将重新检查';
      }
    } catch (_) {
      message = version?.platform == 'android'
          ? '更新失败，请重试；若未允许安装，请在系统设置中开启'
          : '暂时无法打开应用商店，请重试';
    } finally {
      updating = false;
      progress = null;
      _changed();
    }
  }

  Future<String> _downloadApk(AppRelease target) async {
    final download = base.resolve(target.downloadUrl!);
    if (download.origin != base.origin ||
        (download.scheme != 'https' &&
            !(base.scheme == 'http' && base.host == '127.0.0.1')) ||
        target.size <= 0 ||
        target.size > 260 * 1024 * 1024 ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(target.hash)) {
      throw const FormatException('Invalid package descriptor');
    }
    final path = await platform.downloadPath('${target.id}-${target.hash}');
    final completed = File(path);
    if (await completed.exists()) {
      if (await _valid(completed, target)) return path;
      await completed.delete();
    }
    final partial = File('$path.part');
    var offset = await partial.exists() ? await partial.length() : 0;
    if (offset >= target.size) {
      await partial.delete();
      offset = 0;
    }
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    IOSink? sink;
    try {
      final request = await client.getUrl(download);
      request.followRedirects = false;
      request.headers.set('Accept-Encoding', 'identity');
      if (offset > 0) request.headers.set('Range', 'bytes=$offset-');
      final response = await request.close().timeout(
        const Duration(seconds: 20),
      );
      if (response.statusCode == 206) {
        final range = response.headers.value('Content-Range');
        final match = RegExp(
          r'^bytes (\d+)-(\d+)/(\d+)$',
        ).firstMatch(range ?? '');
        if (match == null ||
            int.parse(match[1]!) != offset ||
            int.parse(match[3]!) != target.size) {
          throw const FormatException('Invalid byte range');
        }
      } else if (response.statusCode == 200) {
        offset = 0;
      } else {
        throw const HttpException('Package download failed');
      }
      sink = partial.openWrite(
        mode: offset == 0 ? FileMode.write : FileMode.append,
      );
      var received = offset;
      var flushed = received;
      await for (final chunk in response.timeout(const Duration(seconds: 20))) {
        received += chunk.length;
        if (received > target.size) {
          throw const FormatException('Package too large');
        }
        sink.add(chunk);
        if (received - flushed >= 1024 * 1024) {
          await sink.flush();
          flushed = received;
        }
        progress = received / target.size;
        _changed();
      }
      await sink.flush();
      await sink.close();
      sink = null;
      if (!await _valid(partial, target)) {
        await partial.delete();
        throw const FormatException('Package integrity check failed');
      }
      await partial.rename(path);
      return path;
    } finally {
      if (sink != null) await sink.close();
      client.close(force: true);
    }
  }

  Future<bool> _valid(File file, AppRelease target) async =>
      await file.length() == target.size &&
      await compute(_fileSha256, file.path) == target.hash;
  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}

Future<String> _fileSha256(String path) async =>
    (await sha256.bind(File(path).openRead()).first).toString();
