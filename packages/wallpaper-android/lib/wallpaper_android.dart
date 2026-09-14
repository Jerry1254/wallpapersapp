import 'dart:async';
import 'package:flutter/services.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';

class InstallationIdentity {
  const InstallationIdentity(
    this.publicKeyPem,
    this.fingerprint,
    this.scope,
    this.credentialKeyId,
  );
  final String publicKeyPem, fingerprint, scope;
  final String? credentialKeyId;
}

class AndroidWallpaperPlayback implements Preview, WallpaperApply {
  const AndroidWallpaperPlayback();
  static const _channel = MethodChannel('qingjing/wallpaper_android');
  static String resourceType(WallpaperEffect effect) => switch (effect) {
    WallpaperEffect.staticImage => 'STATIC_IMAGE',
    WallpaperEffect.video => 'VIDEO',
    WallpaperEffect.parallax => 'LAYER_PARALLAX',
  };
  Future<WallpaperCapabilities> capabilities() async {
    final data =
        await _channel.invokeMapMethod<String, dynamic>(
          'playbackCapabilities',
        ) ??
        {};
    WallpaperEffect? effect(dynamic value) => switch (value) {
      'STATIC_IMAGE' => WallpaperEffect.staticImage,
      'VIDEO' => WallpaperEffect.video,
      'LAYER_PARALLAX' => WallpaperEffect.parallax,
      _ => null,
    };
    final targets = <WallpaperEffect, Set<WallpaperTarget>>{};
    for (final entry in ((data['targets'] as Map?) ?? {}).entries) {
      final item = effect(entry.key);
      if (item == null) continue;
      targets[item] = (entry.value as List)
          .map(
            (name) => switch (name) {
              'home' => WallpaperTarget.home,
              'lock' => WallpaperTarget.lock,
              'both' => WallpaperTarget.both,
              _ => null,
            },
          )
          .whereType<WallpaperTarget>()
          .toSet();
    }
    return WallpaperCapabilities(
      platform: ClientPlatform.android,
      osVersion: data['osVersion'] as String?,
      systemChoosesLiveTarget: data['systemChoosesLiveTarget'] == true,
      setupMessage: data['setupMessage'] as String?,
      previewEffects: ((data['previewEffects'] as List?) ?? [])
          .map(effect)
          .whereType<WallpaperEffect>()
          .toSet(),
      targets: targets,
    );
  }

  Future<PlatformResult<void>> _invoke(
    String method,
    Map<String, String> arguments,
  ) async {
    try {
      final data = await _channel.invokeMapMethod<String, dynamic>(
        method,
        arguments,
      );
      final status = switch (data?['status']) {
        'completed' => OperationStatus.completed,
        'cancelled' => OperationStatus.cancelled,
        'unsupported' => OperationStatus.unsupported,
        _ => OperationStatus.unknown,
      };
      return PlatformResult(status, message: data?['message'] as String?);
    } on PlatformException catch (e) {
      return PlatformResult(
        OperationStatus.unknown,
        message: e.message ?? '原生预览或设置暂时不可用',
      );
    }
  }

  @override
  Future<PlatformResult<void>> open(
    String resourceId,
    WallpaperEffect effect,
  ) => _invoke('previewPackage', {
    'installedId': resourceId,
    'resourceType': resourceType(effect),
  });
  @override
  Future<PlatformResult<void>> apply(
    String installedId,
    WallpaperEffect effect,
    WallpaperTarget target,
  ) => _invoke('applyWallpaper', {
    'installedId': installedId,
    'resourceType': resourceType(effect),
    'target': target.name,
  });
}

class SecurePackageDownload {
  const SecurePackageDownload({
    required this.requestId,
    required this.wallpaperId,
    required this.resourceType,
    required this.url,
    required this.descriptor,
  });
  final String requestId, wallpaperId, resourceType;
  final Uri url;
  final Map<String, dynamic> descriptor;
}

class PackageDownloadProgress {
  const PackageDownloadProgress(
    this.requestId,
    this.status,
    this.receivedBytes,
    this.totalBytes,
  );
  final String requestId, status;
  final int receivedBytes, totalBytes;
}

/// Installation private keys, plaintext files and storage paths stay inside Android.
class AndroidPackageInstaller implements PackageInstaller {
  static const _channel = MethodChannel('qingjing/wallpaper_android');
  static const _events = EventChannel('qingjing/wallpaper_downloads');
  final _prepared = <String, SecurePackageDownload>{};
  late final Stream<PackageDownloadProgress> progress = _events
      .receiveBroadcastStream()
      .map((value) {
        final data = Map<String, dynamic>.from(value as Map);
        return PackageDownloadProgress(
          data['requestId'] as String,
          data['status'] as String,
          data['receivedBytes'] as int,
          data['totalBytes'] as int,
        );
      })
      .asBroadcastStream();
  void prepare(SecurePackageDownload download) {
    _prepared[download.requestId] = download;
  }

  Future<Map<String, dynamic>> information() async =>
      await _channel.invokeMapMethod<String, dynamic>('deliveryInfo') ?? {};
  Future<String?> current(String wallpaperId, String resourceType) =>
      _channel.invokeMethod<String>('installedPackage', {
        'wallpaperId': wallpaperId,
        'resourceType': resourceType,
      });
  Future<void> cancel(String requestId) =>
      _channel.invokeMethod<void>('cancelDownload', {'requestId': requestId});
  Future<int> clearUnused() async =>
      await _channel.invokeMethod<int>('clearPackageCache') ?? 0;
  @override
  Future<PlatformResult<String>> install(String downloadId) async {
    final request = _prepared.remove(downloadId);
    if (request == null)
      return const PlatformResult(
        OperationStatus.unknown,
        message: '下载请求已失效，请重新下载',
      );
    try {
      final result = await _channel
          .invokeMapMethod<String, dynamic>('installPackage', {
            'requestId': request.requestId,
            'wallpaperId': request.wallpaperId,
            'resourceType': request.resourceType,
            'url': request.url.toString(),
            'descriptor': request.descriptor,
          });
      final id = result?['installedId'] as String?;
      if (id == null ||
          result?['wallpaperId'] != request.wallpaperId ||
          result?['resourceType'] != request.resourceType) {
        return const PlatformResult(
          OperationStatus.unknown,
          message: '安装结果不可确认，请检查本地资源',
        );
      }
      return PlatformResult(OperationStatus.completed, value: id);
    } on PlatformException catch (e) {
      return PlatformResult(
        e.code == 'DOWNLOAD_CANCELLED'
            ? OperationStatus.cancelled
            : OperationStatus.unknown,
        message: e.message ?? '资源安装失败，请重试',
      );
    }
  }
}

/// Catalog previews use their own restricted store, never the system wallpaper store.
class AndroidDetailPreview {
  const AndroidDetailPreview();
  static const _channel = MethodChannel('qingjing/wallpaper_android');
  Future<String> prepare(SecurePackageDownload request) async {
    final result = await _channel
        .invokeMapMethod<String, dynamic>('installDetailPreview', {
          'requestId': request.requestId,
          'wallpaperId': request.wallpaperId,
          'resourceType': request.resourceType,
          'url': request.url.toString(),
          'descriptor': request.descriptor,
        });
    final id = result?['previewId'] as String?;
    if (id == null ||
        id.length > 160 ||
        !RegExp(
          r'^[1-9][0-9]{0,18}-(VIDEO|LAYER_PARALLAX)-[1-9][0-9]{0,18}-[a-f0-9]{64}$',
        ).hasMatch(id) ||
        result?['resourceType'] != request.resourceType) {
      throw PlatformException(code: 'PACKAGE_INVALID', message: '预览资源暂时不可用');
    }
    return id;
  }

  Future<void> cancel(String requestId) => _channel.invokeMethod<void>(
    'cancelDetailPreview',
    {'requestId': requestId},
  );
}

/// Trial handles are opaque and never identify a formal installed package.
class AndroidTrialPreview {
  const AndroidTrialPreview();
  static const _channel = MethodChannel('qingjing/wallpaper_android');
  Future<String> prepare(SecurePackageDownload request) async {
    final response = await _channel
        .invokeMapMethod<String, dynamic>('installPreview', {
          'requestId': request.requestId,
          'wallpaperId': request.wallpaperId,
          'resourceType': request.resourceType,
          'url': request.url.toString(),
          'descriptor': request.descriptor,
        });
    final id = response?['trialId'] as String?;
    if (id == null ||
        response?['resourceType'] != request.resourceType ||
        !RegExp(r'^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$').hasMatch(id)) {
      throw PlatformException(code: 'PACKAGE_INVALID', message: '试用资源暂时不可用');
    }
    return id;
  }

  Future<Map<String, dynamic>?> recover() =>
      _channel.invokeMapMethod<String, dynamic>('recoverTrial');
  Future<Map<String, dynamic>> open(String id, String type) async =>
      await _channel.invokeMapMethod<String, dynamic>('openTrial', {
        'trialId': id,
        'resourceType': type,
      }) ??
      {'status': 'unknown', 'message': '试用结果不可确认'};
  Future<void> cancel(String requestId) => _channel.invokeMethod<void>(
    'cancelPreviewDownload',
    {'requestId': requestId},
  );
  Future<void> discard(String id) =>
      _channel.invokeMethod<void>('discardTrial', {'trialId': id});
}

class AndroidDeviceIdentity implements DeviceIdentity {
  const AndroidDeviceIdentity();
  static const _channel = MethodChannel('qingjing/wallpaper_android');
  Future<InstallationIdentity> installation() async {
    final data = await _channel.invokeMapMethod<String, dynamic>('identity');
    if (data == null) throw StateError('Installation identity unavailable');
    return InstallationIdentity(
      data['publicKeyPem'] as String,
      data['fingerprint'] as String,
      data['scope'] as String,
      data['credentialKeyId'] as String?,
    );
  }

  Future<Map<String, String>> encryptionPublicKey() async {
    final value = await _channel.invokeMapMethod<String, String>(
      'encryptionPublicKey',
    );
    if (value == null ||
        value['keyAlgorithm'] != 'RSA-OAEP-SHA256-MGF1-SHA1' ||
        value['publicKeyPem'] == null ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(value['fingerprint'] ?? '')) {
      throw StateError('Installation encryption key unavailable');
    }
    return value;
  }

  Future<void> rememberCredential(String id) => _channel.invokeMethod<void>(
    'rememberCredential',
    {'credentialKeyId': id},
  );
  Future<String> signPayload(String payload) async {
    final proof = await _channel.invokeMethod<String>('sign', {
      'payload': payload,
    });
    if (proof == null) throw StateError('Signature unavailable');
    return proof;
  }

  @override
  Future<PlatformResult<String>> publicKey() async => PlatformResult(
    OperationStatus.completed,
    value: (await installation()).publicKeyPem,
  );
  @override
  Future<PlatformResult<String>> sign(String payload) async => PlatformResult(
    OperationStatus.completed,
    value: await signPayload(payload),
  );
}
