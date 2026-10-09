import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../device/device_session.dart';

class DownloadState {
  const DownloadState({
    this.wallpaperId,
    this.deliveryPlatform,
    this.resourceType,
    this.requestId,
    this.afterRedemption = false,
    this.busy = false,
    this.status = '',
    this.received = 0,
    this.total = 0,
    this.message,
    this.installedId,
  });
  final String? wallpaperId,
      deliveryPlatform,
      resourceType,
      requestId,
      message,
      installedId;
  final bool busy, afterRedemption;
  final String status;
  final int received, total;
}

class DownloadManager extends ValueNotifier<DownloadState> {
  DownloadManager(
    this.sessions,
    this.apiBase, {
    AndroidPackageInstaller? installer,
    IosMediaInstaller? iosInstaller,
    bool? ios,
  }) : installer = installer ?? AndroidPackageInstaller(),
       iosInstaller = iosInstaller ?? const IosMediaInstaller(),
       isIos = ios ?? Platform.isIOS,
       super(const DownloadState());
  final DeviceSessionManager sessions;
  final Uri apiBase;
  final AndroidPackageInstaller installer;
  final IosMediaInstaller iosInstaller;
  final bool isIos;
  bool _disposed = false;
  @override
  set value(DownloadState state) {
    if (!_disposed) super.value = state;
  }

  bool _cancelRequested = false;
  bool _operationActive = false;
  StreamSubscription<PackageDownloadProgress>? _progress;
  Future<String?> current(String wallpaper, String type) => isIos
      ? iosInstaller.current(wallpaper, type)
      : installer.current(wallpaper, type);
  Future<void> download(
    String wallpaper,
    String deliveryPlatform,
    String type, {
    bool afterRedemption = false,
  }) async {
    if (value.busy || _operationActive) return;
    _operationActive = true;
    final requestId = requestUuid();
    _cancelRequested = false;
    value = DownloadState(
      wallpaperId: wallpaper,
      deliveryPlatform: deliveryPlatform,
      resourceType: type,
      requestId: requestId,
      afterRedemption: afterRedemption,
      busy: true,
      status: 'preparing',
    );
    try {
      if (isIos) {
        final descriptor = await sessions.authenticated(
          '/device/wallpapers/$wallpaper/download-tickets',
          method: 'POST',
          signed: true,
          body: jsonEncode({
            'deliveryPlatform': deliveryPlatform,
            'resourceType': type,
          }),
        );
        if (_cancelRequested) return;
        final expectedMode = type == 'LIVE_PHOTO'
            ? 'LIVE_PHOTO'
            : type == 'STATIC_IMAGE'
            ? 'STATIC_IMAGE'
            : null;
        if (expectedMode == null ||
            descriptor['deliveryMode'] != expectedMode ||
            descriptor['wallpaperId'] != wallpaper ||
            (descriptor['resourceVersion'] as Map?)?['platform'] !=
                deliveryPlatform ||
            (descriptor['resourceVersion'] as Map?)?['resourceType'] != type) {
          throw const FormatException('Invalid iOS delivery descriptor');
        }
        final installed = await iosInstaller.save(
          requestId: requestId,
          wallpaperId: wallpaper,
          resourceType: type,
          apiOrigin: apiBase,
          descriptor: descriptor,
        );
        value = DownloadState(
          wallpaperId: wallpaper,
          deliveryPlatform: deliveryPlatform,
          resourceType: type,
          requestId: requestId,
          afterRedemption: afterRedemption,
          status: 'completed',
          installedId: installed,
          message: '已保存到相册，请设置',
        );
        return;
      }
      _progress = installer.progress.listen((event) {
        if (event.requestId != requestId || !value.busy) return;
        value = DownloadState(
          wallpaperId: wallpaper,
          deliveryPlatform: deliveryPlatform,
          resourceType: type,
          requestId: requestId,
          afterRedemption: afterRedemption,
          busy: true,
          status: event.status,
          received: event.receivedBytes,
          total: event.totalBytes,
        );
      }, onError: (_) {});
      final binding = await sessions.ensureEncryptionKey();
      if (_cancelRequested) return;
      final descriptor = await sessions.authenticated(
        '/device/wallpapers/$wallpaper/download-tickets',
        method: 'POST',
        signed: true,
        body: jsonEncode({
          'deliveryPlatform': deliveryPlatform,
          'resourceType': type,
        }),
      );
      if (_cancelRequested) return;
      if (descriptor['deliveryMode'] != 'SECURE_PACKAGE' ||
          descriptor['wallpaperId'] != wallpaper ||
          descriptor['downloadUrl'] != '/api/v1/delivery/files' ||
          (descriptor['package'] as Map?)?['encryptionKeySha256'] != binding ||
          (descriptor['resourceVersion'] as Map?)?['platform'] !=
              deliveryPlatform ||
          (descriptor['resourceVersion'] as Map?)?['resourceType'] != type) {
        throw const FormatException('Invalid delivery descriptor');
      }
      // Resolve only the fixed protected path against this application's configured origin.
      installer.prepare(
        SecurePackageDownload(
          requestId: requestId,
          wallpaperId: wallpaper,
          resourceType: type,
          url: apiBase.resolve('/api/v1/delivery/files'),
          descriptor: descriptor,
        ),
      );
      final installed = await installer.install(requestId);
      value = DownloadState(
        wallpaperId: wallpaper,
        deliveryPlatform: deliveryPlatform,
        resourceType: type,
        requestId: requestId,
        afterRedemption: afterRedemption,
        status: installed.status == OperationStatus.completed
            ? 'completed'
            : 'failed',
        installedId: installed.value,
        message: installed.status == OperationStatus.completed
            ? '资源已校验并安装'
            : installed.message,
      );
    } catch (e) {
      value = DownloadState(
        wallpaperId: wallpaper,
        deliveryPlatform: deliveryPlatform,
        resourceType: type,
        requestId: requestId,
        afterRedemption: afterRedemption,
        status: 'failed',
        message: e is DeviceApiError
            ? switch (e.code) {
                'ENTITLEMENT_REQUIRED' => '请先获得此壁纸权益，再下载资源',
                'SECURE_PACKAGE_NOT_READY' => '暂无与当前手机匹配的安全资源，请稍后重试',
                'RATE_LIMITED' => '操作过于频繁，请稍后重试',
                _ => e.message,
              }
            : e is PlatformException
            ? (e.message ?? '壁纸保存失败，请重试')
            : '资源信息或安装不可用，请重试',
      );
    } finally {
      await _progress?.cancel();
      _progress = null;
      if (value.busy) {
        value = DownloadState(
          wallpaperId: wallpaper,
          deliveryPlatform: deliveryPlatform,
          resourceType: type,
          afterRedemption: afterRedemption,
          status: 'cancelled',
          message: '下载已取消，可重新下载',
        );
      }
      _operationActive = false;
    }
  }

  Future<void> cancel() async {
    _cancelRequested = true;
    final id = value.requestId;
    if (id != null) {
      if (isIos) {
        await iosInstaller.cancel(id);
      } else {
        await installer.cancel(id);
      }
    }
  }

  Future<void> retry() async {
    final current = value;
    final wallpaper = current.wallpaperId;
    final platform = current.deliveryPlatform;
    final type = current.resourceType;
    if (current.busy || wallpaper == null || platform == null || type == null) {
      return;
    }
    await download(
      wallpaper,
      platform,
      type,
      afterRedemption: current.afterRedemption,
    );
  }

  void dismissResult() {
    if (!value.busy) value = const DownloadState();
  }

  Future<int> clearUnused() async {
    if (value.busy || _operationActive) throw StateError('Download active');
    final removed = isIos
        ? await iosInstaller.clearUnused()
        : await installer.clearUnused();
    value = const DownloadState(status: 'cacheCleared');
    return removed;
  }

  @override
  void dispose() {
    _cancelRequested = true;
    _disposed = true;
    _progress?.cancel();
    super.dispose();
  }
}
