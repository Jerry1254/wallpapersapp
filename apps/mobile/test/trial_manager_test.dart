import 'dart:async';
import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/detail/trial_manager.dart';
import 'package:qingjing_wallpaper/detail/detail_screen.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/downloads/download_manager.dart';
import 'package:qingjing_wallpaper/downloads/download_panel.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'catalog_test.dart' show FakeCatalog;

class PreviewCatalog extends FakeCatalog {
  PreviewCatalog({this.accessType = 'REDEEM'});
  final String accessType;
  @override
  Future<Wallpaper> detail(String id) async => Wallpaper.fromJson({
    'id': id,
    'title': '试用入口测试',
    'accessType': accessType,
    'cover': {'contentUrl': '/image'},
    'availableCapabilities': [
      {
        'deliveryPlatform': 'UNIVERSAL',
        'resourceType': 'STATIC_IMAGE',
        'placements': ['HOME'],
      },
    ],
  });
}

class PreviewCapabilities extends AndroidWallpaperPlayback {
  @override
  Future<WallpaperCapabilities> capabilities() async =>
      const WallpaperCapabilities(
        platform: ClientPlatform.android,
        previewEffects: {WallpaperEffect.staticImage},
        targets: {
          WallpaperEffect.staticImage: {WallpaperTarget.home},
        },
      );
}

class UnusedTransport implements DeviceTransport {
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) => throw UnimplementedError();
}

class PreviewSessions extends DeviceSessionManager {
  PreviewSessions() : super(UnusedTransport());
  bool owned = false, ownedOnSecondPage = false;
  int issuances = 0, bindings = 0, entitlementChecks = 0;
  Future<String>? binding;
  Map<String, dynamic> descriptor = {
    'deliveryMode': 'APP_PREVIEW',
    'purpose': 'APP_PREVIEW',
    'durationSeconds': 120,
    'wallpaperId': '10',
    'downloadUrl': '/api/v1/preview/files',
    'package': {'formatVersion': 3, 'encryptionKeySha256': 'binding'},
    'resourceVersion': {'platform': 'ANDROID', 'resourceType': 'VIDEO'},
  };
  @override
  Future<String> ensureEncryptionKey() async {
    bindings++;
    return binding == null ? 'binding' : await binding!;
  }

  @override
  Future<Map<String, dynamic>> authenticated(
    String path, {
    String method = 'GET',
    String? body,
    bool signed = false,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    if (path.startsWith('/device/me/entitlements')) {
      entitlementChecks++;
      return {
        'items': (owned || (ownedOnSecondPage && path.contains('page=2&')))
            ? [
                {
                  'wallpaper': {'id': '10'},
                },
              ]
            : [],
        'page': {'totalPages': ownedOnSecondPage ? 2 : 0},
      };
    }
    expect(path, '/device/wallpapers/10/preview-tickets');
    expect(method, 'POST');
    expect(signed, true);
    expect(jsonDecode(body!), {
      'deliveryPlatform': 'ANDROID',
      'resourceType': 'VIDEO',
    });
    issuances++;
    return descriptor;
  }
}

class PreviewInstaller extends AndroidPackageInstaller {
  final stream = StreamController<PackageDownloadProgress>.broadcast(
    sync: true,
  );
  @override
  Stream<PackageDownloadProgress> get progress => stream.stream;
  @override
  Future<Map<String, dynamic>> information() async => {'osVersion': '13'};
  @override
  Future<String?> current(String wallpaperId, String resourceType) async =>
      'formal-installed';
}

class PreviewNative extends AndroidTrialPreview {
  int preparations = 0, opens = 0, discards = 0;
  SecurePackageDownload? request;
  Future<String>? prepared;
  bool failOpen = false;
  @override
  Future<String> prepare(SecurePackageDownload download) async {
    preparations++;
    request = download;
    return prepared == null ? 'trial-handle' : await prepared!;
  }

  @override
  Future<Map<String, dynamic>> open(String id, String type) async {
    opens++;
    expect(id, 'trial-handle');
    expect(type, 'VIDEO');
    if (failOpen) throw PlatformException(code: 'PREVIEW_UNAVAILABLE');
    return {'status': 'completed', 'message': '两分钟试用已到期'};
  }

  @override
  Future<void> discard(String id) async {
    expect(id, 'trial-handle');
    discards++;
  }

  @override
  Future<void> cancel(String requestId) async {}
}

void main() {
  for (final owned in [false, true]) {
    testWidgets(owned ? '已获得详情提供正式预览，隐藏试用入口' : '未获得详情隐藏试用及正式下载入口', (
      tester,
    ) async {
      final sessions = PreviewSessions()..owned = owned,
          installer = PreviewInstaller();
      String? previewType;
      final trials = TrialManager(
        sessions,
        Uri.parse('https://example.test/api/v1'),
        installer,
        native: PreviewNative(),
      );
      final downloads = DownloadManager(
        sessions,
        Uri.parse('https://example.test/api/v1'),
        installer: installer,
      );
      await tester.pumpWidget(
        MaterialApp(
          home: DetailScreen(
            repository: PreviewCatalog(),
            id: '10',
            trials: trials,
            downloads: downloads,
            playback: PreviewCapabilities(),
            detailPreviewBuilder:
                (
                  manager,
                  wallpaperId,
                  deliveryPlatform,
                  resourceType,
                  cover,
                  active,
                ) {
                  expect(deliveryPlatform, 'UNIVERSAL');
                  previewType = resourceType;
                  return cover;
                },
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('试用两分钟'), findsNothing);
      expect(find.text('试用 2 分钟'), findsNothing);
      expect(find.text(owned ? '设置壁纸' : '下载壁纸'), findsOneWidget);
      expect(previewType, 'STATIC_IMAGE');
      expect(sessions.issuances, 0);
      expect(find.byType(DownloadPanel), findsNothing);
      await tester.pumpWidget(const SizedBox());
      trials.dispose();
      downloads.dispose();
      await installer.stream.close();
    });
  }
  testWidgets('免费详情不查询权益，也不显示确认权益状态', (tester) async {
    final sessions = PreviewSessions(), installer = PreviewInstaller();
    final trials = TrialManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer,
      native: PreviewNative(),
    );
    final downloads = DownloadManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer: installer,
    );
    await tester.pumpWidget(
      MaterialApp(
        home: DetailScreen(
          repository: PreviewCatalog(accessType: 'FREE'),
          id: '10',
          trials: trials,
          downloads: downloads,
          playback: PreviewCapabilities(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(sessions.entitlementChecks, 0);
    expect(find.text('正在确认权益'), findsNothing);
    expect(find.text('设置壁纸'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    trials.dispose();
    downloads.dispose();
    await installer.stream.close();
  });
  test('未获得试用只申请签名预览票据，使用固定同源路径并在结束时清理', () async {
    final sessions = PreviewSessions(),
        installer = PreviewInstaller(),
        native = PreviewNative();
    final manager = TrialManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer,
      native: native,
    );
    await manager.start('10', 'ANDROID', 'VIDEO');
    expect(
      native.request!.url.toString(),
      'https://example.test/api/v1/preview/files',
    );
    expect(sessions.issuances, 1);
    expect(native.opens, 1);
    expect(native.discards, 1);
    expect(manager.value.busy, false);
    expect(manager.value.message, '两分钟试用已到期');
    manager.dispose();
    await installer.stream.close();
  });
  test('已获得权益在后续分页时仍走正式入口，不申请试用资源', () async {
    final sessions = PreviewSessions()..ownedOnSecondPage = true,
        installer = PreviewInstaller(),
        native = PreviewNative();
    final manager = TrialManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer,
      native: native,
    );
    await manager.start('10', 'ANDROID', 'VIDEO');
    expect(manager.value.status, 'owned');
    expect(sessions.issuances, 0);
    expect(sessions.bindings, 0);
    expect(native.preparations, 0);
    manager.dispose();
    await installer.stream.close();
  });
  test('正式包、错误目的和外部交付地址均不能进入试用安装', () async {
    for (final changes in [
      {'deliveryMode': 'SECURE_PACKAGE'},
      {'purpose': 'SYSTEM_WALLPAPER'},
      {'downloadUrl': 'https://other.test/api/v1/preview/files'},
      {
        'package': {'formatVersion': 2, 'encryptionKeySha256': 'binding'},
      },
    ]) {
      final sessions = PreviewSessions(),
          installer = PreviewInstaller(),
          native = PreviewNative();
      sessions.descriptor.addAll(changes);
      final manager = TrialManager(
        sessions,
        Uri.parse('https://example.test/api/v1'),
        installer,
        native: native,
      );
      await manager.start('10', 'ANDROID', 'VIDEO');
      expect(manager.value.status, 'failed');
      expect(native.preparations, 0);
      manager.dispose();
      await installer.stream.close();
    }
  });
  test('准备阶段取消不申请票据，下载恰好完成的取消也不会打开画面', () async {
    final sessions = PreviewSessions(),
        installer = PreviewInstaller(),
        native = PreviewNative(),
        binding = Completer<String>();
    sessions.binding = binding.future;
    final manager = TrialManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer,
      native: native,
    );
    final operation = manager.start('10', 'ANDROID', 'VIDEO');
    await Future<void>.delayed(Duration.zero);
    await manager.cancel();
    binding.complete('binding');
    await operation;
    expect(sessions.issuances, 0);
    expect(native.preparations, 0);
    final prepared = Completer<String>();
    sessions.binding = null;
    native.prepared = prepared.future;
    final second = manager.start('10', 'ANDROID', 'VIDEO');
    await Future<void>.delayed(Duration.zero);
    await manager.cancel();
    prepared.complete('trial-handle');
    await second;
    expect(native.opens, 0);
    expect(native.discards, 1);
    manager.dispose();
    await installer.stream.close();
  });
  test('原生画面无法打开时仍删除已经准备的临时资源', () async {
    final sessions = PreviewSessions(),
        installer = PreviewInstaller(),
        native = PreviewNative()..failOpen = true;
    final manager = TrialManager(
      sessions,
      Uri.parse('https://example.test/api/v1'),
      installer,
      native: native,
    );
    await manager.start('10', 'ANDROID', 'VIDEO');
    expect(manager.value.status, 'failed');
    expect(native.discards, 1);
    manager.dispose();
    await installer.stream.close();
  });
}
