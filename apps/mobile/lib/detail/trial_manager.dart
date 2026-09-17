import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import '../device/device_session.dart';

class TrialState {
  const TrialState({
    this.busy = false,
    this.status = '',
    this.message,
    this.received = 0,
    this.total = 0,
  });
  final bool busy;
  final String status;
  final String? message;
  final int received, total;
}

class TrialManager extends ValueNotifier<TrialState> {
  TrialManager(
    this.sessions,
    this.apiBase,
    this.installer, {
    AndroidTrialPreview? native,
  }) : native = native ?? const AndroidTrialPreview(),
       super(const TrialState());
  final DeviceSessionManager sessions;
  final Uri apiBase;
  final AndroidPackageInstaller installer;
  final AndroidTrialPreview native;
  String? _requestId;
  bool _cancelled = false, _disposed = false;
  StreamSubscription<PackageDownloadProgress>? _progress;
  void _update(TrialState state) {
    if (!_disposed) value = state;
  }

  Future<bool> isOwned(String wallpaper) async {
    var page = 1;
    while (true) {
      final data = await sessions.authenticated(
        '/device/me/entitlements?page=$page&pageSize=100',
      );
      final items = data['items'] as List;
      if (items.any((item) => (item['wallpaper'] as Map)['id'] == wallpaper)) {
        return true;
      }
      final pages = (data['page'] as Map)['totalPages'] as int;
      if (page >= pages) return false;
      if (page >= 1000) throw const DeviceApiError(0, 'INVALID_RESPONSE');
      page++;
    }
  }

  Future<void> start(
    String wallpaper,
    String deliveryPlatform,
    String type,
  ) async {
    if (value.busy || _disposed) return;
    final requestId = requestUuid();
    String? trialId;
    _requestId = requestId;
    _cancelled = false;
    _update(const TrialState(busy: true, status: 'preparing'));
    try {
      if (await isOwned(wallpaper)) {
        _update(const TrialState(status: 'owned', message: '已获得此壁纸，请使用正式资源预览'));
        return;
      }
      if (_cancelled) return;
      _progress = installer.progress.listen((event) {
        if (event.requestId == requestId && !_disposed && value.busy) {
          _update(
            TrialState(
              busy: true,
              status: event.status,
              received: event.receivedBytes,
              total: event.totalBytes,
            ),
          );
        }
      }, onError: (_) {});
      final binding = await sessions.ensureEncryptionKey();
      if (_cancelled) return;
      final descriptor = await sessions.authenticated(
        '/device/wallpapers/$wallpaper/preview-tickets',
        method: 'POST',
        signed: true,
        body: jsonEncode({
          'deliveryPlatform': deliveryPlatform,
          'resourceType': type,
        }),
      );
      if (_cancelled) return;
      if (descriptor['deliveryMode'] != 'APP_PREVIEW' ||
          descriptor['purpose'] != 'APP_PREVIEW' ||
          descriptor['durationSeconds'] != 120 ||
          descriptor['wallpaperId'] != wallpaper ||
          descriptor['downloadUrl'] != '/api/v1/preview/files' ||
          (descriptor['package'] as Map?)?['formatVersion'] != 3 ||
          (descriptor['package'] as Map?)?['encryptionKeySha256'] != binding ||
          (descriptor['resourceVersion'] as Map?)?['platform'] !=
              deliveryPlatform ||
          (descriptor['resourceVersion'] as Map?)?['resourceType'] != type) {
        throw const FormatException();
      }
      final trial = await native.prepare(
        SecurePackageDownload(
          requestId: requestId,
          wallpaperId: wallpaper,
          resourceType: type,
          url: apiBase.resolve('/api/v1/preview/files'),
          descriptor: descriptor,
        ),
      );
      trialId = trial;
      if (_cancelled) {
        return;
      }
      await _progress?.cancel();
      _progress = null;
      _update(const TrialState(busy: true, status: 'previewing'));
      final outcome = await native.open(trial, type);
      _update(
        TrialState(
          status: outcome['status'] as String? ?? 'unknown',
          message: outcome['message'] as String?,
        ),
      );
    } catch (error) {
      _update(
        TrialState(
          status: _cancelled ? 'cancelled' : 'failed',
          message: error is DeviceApiError
              ? switch (error.code) {
                  'PREVIEW_RESOURCE_NOT_READY' => '此作品的试用资源暂时不可用，请稍后重试',
                  'RATE_LIMITED' => '试用操作过于频繁，请稍后重试',
                  _ => error.message,
                }
              : error is PlatformException
              ? error.message ?? '试用暂时不可用，请重试'
              : '试用暂时不可用，请重试',
        ),
      );
    } finally {
      if (trialId != null) {
        try {
          await native.discard(trialId);
        } catch (_) {}
      }
      await _progress?.cancel();
      _progress = null;
      _requestId = null;
      if (!_disposed && value.busy) {
        _update(const TrialState(status: 'cancelled', message: '试用准备已取消'));
      }
    }
  }

  Future<void> cancel() async {
    _cancelled = true;
    final id = _requestId;
    if (id != null) await native.cancel(id);
  }

  @override
  void dispose() {
    _disposed = true;
    _cancelled = true;
    final id = _requestId;
    if (id != null) unawaited(native.cancel(id).catchError((_) {}));
    _progress?.cancel();
    super.dispose();
  }
}
