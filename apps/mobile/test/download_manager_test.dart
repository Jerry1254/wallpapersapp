import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/downloads/download_manager.dart';

class NoTransport implements DeviceTransport {
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) => throw UnimplementedError();
}

class Sessions extends DeviceSessionManager {
  Sessions() : super(NoTransport());
  Future<String>? binding;
  int calls = 0;
  DeviceApiError? error;
  Map<String, dynamic> descriptor = {
    'deliveryMode': 'SECURE_PACKAGE',
    'wallpaperId': '10',
    'downloadUrl': '/api/v1/delivery/files',
    'package': {'encryptionKeySha256': 'key-hash'},
    'resourceVersion': {'resourceType': 'STATIC_IMAGE'},
  };
  @override
  Future<String> ensureEncryptionKey() async =>
      binding == null ? 'key-hash' : await binding!;
  @override
  Future<Map<String, dynamic>> authenticated(
    String path, {
    String method = 'GET',
    String? body,
    bool signed = false,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    expect(signed, true);
    expect(method, 'POST');
    expect(body, contains('"osVersion":"15"'));
    expect(body, contains('"supportedResourceTypes":["STATIC_IMAGE"]'));
    calls++;
    if (error != null) throw error!;
    return descriptor;
  }
}

class Installer extends AndroidPackageInstaller {
  final stream = StreamController<PackageDownloadProgress>.broadcast(
    sync: true,
  );
  SecurePackageDownload? prepared;
  final installed = Completer<PlatformResult<String>>();
  int cancels = 0;
  @override
  Stream<PackageDownloadProgress> get progress => stream.stream;
  @override
  Future<Map<String, dynamic>> information() async => {
    'osVersion': '15',
    'sdkVersion': '35',
  };
  @override
  void prepare(SecurePackageDownload request) {
    prepared = request;
  }

  @override
  Future<PlatformResult<String>> install(String downloadId) => installed.future;
  @override
  Future<void> cancel(String requestId) async {
    cancels++;
  }
}

void main() {
  test('只有原生安全安装完成才记录下载成功，过滤其他下载事件', () async {
    final installer = Installer(), sessions = Sessions();
    final manager = DownloadManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer: installer,
    );
    final operation = manager.download('10', 'STATIC_IMAGE');
    await Future<void>.delayed(Duration.zero);
    expect(
      installer.prepared?.url.toString(),
      'https://example.test/api/v1/delivery/files',
    );
    expect(manager.value.installedId, isNull);
    installer.stream.add(
      const PackageDownloadProgress('other', 'completed', 100, 100),
    );
    expect(manager.value.installedId, isNull);
    installer.stream.add(
      PackageDownloadProgress(
        installer.prepared!.requestId,
        'verifying',
        100,
        100,
      ),
    );
    expect(manager.value.busy, true);
    expect(manager.value.installedId, isNull);
    expect(manager.value.status, 'verifying');
    installer.installed.complete(
      const PlatformResult(
        OperationStatus.completed,
        value: 'verified-version',
      ),
    );
    await operation;
    expect(manager.value.installedId, 'verified-version');
    expect(manager.value.busy, false);
    manager.dispose();
    await installer.stream.close();
  });
  test('准备阶段取消不申请票据，不启动安装', () async {
    final binding = Completer<String>(),
        sessions = Sessions(),
        installer = Installer();
    sessions.binding = binding.future;
    final manager = DownloadManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer: installer,
    );
    final operation = manager.download('10', 'STATIC_IMAGE');
    await manager.cancel();
    binding.complete('key-hash');
    await operation;
    expect(sessions.calls, 0);
    expect(installer.prepared, isNull);
    expect(manager.value.status, 'cancelled');
    manager.dispose();
    await installer.stream.close();
  });
  test('描述要求固定同源路径、公钥与资源类型，不转发外部票据', () async {
    for (final changed in [
      {'downloadUrl': 'https://other.test/file'},
      {
        'package': {'encryptionKeySha256': 'other-key'},
      },
      {
        'resourceVersion': {'resourceType': 'VIDEO'},
      },
    ]) {
      final sessions = Sessions(), installer = Installer();
      sessions.descriptor.addAll(changed);
      final manager = DownloadManager(
        sessions,
        Uri.parse('https://example.test/api/v1'),
        installer: installer,
      );
      await manager.download('10', 'STATIC_IMAGE');
      expect(installer.prepared, isNull);
      expect(manager.value.status, 'failed');
      manager.dispose();
      await installer.stream.close();
    }
  });
  test('无权益和不兼容包不启动下载，可重新申请，原生失败不记已下载', () async {
    final sessions = Sessions(), installer = Installer();
    sessions.error = const DeviceApiError(403, 'ENTITLEMENT_REQUIRED');
    final manager = DownloadManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer: installer,
    );
    await manager.download('10', 'STATIC_IMAGE');
    expect(manager.value.message, contains('权益'));
    expect(installer.prepared, isNull);
    sessions.error = null;
    installer.installed.complete(
      const PlatformResult(OperationStatus.unknown, message: '空间不足'),
    );
    await manager.download('10', 'STATIC_IMAGE');
    expect(sessions.calls, 2);
    expect(manager.value.installedId, isNull);
    expect(manager.value.message, '空间不足');
    manager.dispose();
    await installer.stream.close();
  });
}
