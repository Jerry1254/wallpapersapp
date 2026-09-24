import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/detail/delivery.dart';
import 'package:qingjing_wallpaper/detail/detail_screen.dart';
import 'package:qingjing_wallpaper/design_system/qj_components.dart';
import 'package:qingjing_wallpaper/downloads/download_manager.dart';
import 'package:qingjing_wallpaper/downloads/download_panel.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'catalog_test.dart' show FakeCatalog;

Wallpaper wallpaper(List<Map<String, dynamic>> capabilities) =>
    Wallpaper.fromJson({
      'id': '1',
      'title': '静态测试',
      'cover': {'contentUrl': '/image'},
      'availableCapabilities': capabilities,
    });

class DetailCatalog extends FakeCatalog {
  int attempts = 0;
  bool fail = false;
  @override
  Future<Wallpaper> detail(String id) async {
    attempts++;
    if (fail) throw const ApiFailure(404, 'WALLPAPER_NOT_FOUND');
    return wallpaper([
      {
        'deliveryPlatform': 'UNIVERSAL',
        'resourceType': 'STATIC_IMAGE',
        'placements': ['HOME'],
      },
    ]);
  }
}

class DualEffectDetailCatalog extends FakeCatalog {
  @override
  Future<Wallpaper> detail(String id) async => wallpaper([
    {
      'deliveryPlatform': 'ANDROID',
      'resourceType': 'LAYER_PARALLAX',
      'placements': ['HOME'],
    },
    {
      'deliveryPlatform': 'ANDROID',
      'resourceType': 'VIDEO',
      'placements': ['HOME'],
    },
  ]);
}

class MultiFormatDetailCatalog extends FakeCatalog {
  @override
  Future<Wallpaper> detail(String id) async => wallpaper([
    {
      'deliveryPlatform': 'UNIVERSAL',
      'resourceType': 'STATIC_IMAGE',
      'placements': ['HOME', 'LOCK'],
    },
    {
      'deliveryPlatform': 'IOS',
      'resourceType': 'LIVE_PHOTO',
      'placements': ['LOCK'],
    },
    {
      'deliveryPlatform': 'ANDROID',
      'resourceType': 'VIDEO',
      'placements': ['HOME'],
    },
    {
      'deliveryPlatform': 'ANDROID',
      'resourceType': 'LAYER_PARALLAX',
      'placements': ['HOME'],
    },
  ]);
}

class _UnusedTransport implements DeviceTransport {
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) => throw UnimplementedError();
}

class _CurrentInstaller extends AndroidPackageInstaller {
  final controller = StreamController<PackageDownloadProgress>.broadcast();

  @override
  Stream<PackageDownloadProgress> get progress => controller.stream;

  @override
  Future<String?> current(String wallpaperId, String resourceType) async =>
      null;
}

void main() {
  test('详情按后台资源展示全部形式，不用设备能力提前过滤', () {
    final item = wallpaper([
      {
        'deliveryPlatform': 'IOS',
        'resourceType': 'LIVE_PHOTO',
        'placements': ['HOME'],
      },
      {
        'deliveryPlatform': 'ANDROID',
        'resourceType': 'VIDEO',
        'placements': ['HOME'],
      },
      {
        'deliveryPlatform': 'UNIVERSAL',
        'resourceType': 'STATIC_IMAGE',
        'placements': ['HOME', 'LOCK'],
      },
      {
        'deliveryPlatform': 'ANDROID',
        'resourceType': 'FUTURE',
        'placements': ['HOME'],
      },
    ]);
    expect(deliveryOptions(item).map((option) => option.label), [
      '安卓动态壁纸',
      '苹果动态壁纸',
      '静态壁纸',
    ]);
    expect(deliveryEffects(item, ClientPlatform.unknown), [
      WallpaperEffect.video,
      WallpaperEffect.staticImage,
    ]);
    expect(item.capabilityLabels, ['安卓动态壁纸', '苹果动态壁纸', '静态壁纸']);
  });
  testWidgets('详情缺失提示且可恢复，不显示假预览或可兑换操作', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = DetailCatalog()..fail = true;
    await tester.pumpWidget(
      MaterialApp(
        home: DetailScreen(repository: repo, id: '1'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('内容不存在或已经下线'), findsOneWidget);
    repo.fail = false;
    await tester.tap(find.text('重新加载'));
    await tester.pumpAndSettle();
    expect(repo.attempts, 2);
    expect(find.text('作品封面 · 非原生效果预览'), findsNothing);
    expect(find.text('平台'), findsNothing);
    expect(find.text('资源'), findsNothing);
    expect(find.text('下载壁纸'), findsOneWidget);
  });

  testWidgets('动态作品设置前可选动态或静态，选择结果只返回一种资源形式', (tester) async {
    WallpaperEffect? selected;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => FilledButton(
              onPressed: () async {
                selected = await showWallpaperEffectPicker(context, const [
                  WallpaperEffect.video,
                  WallpaperEffect.staticImage,
                ]);
              },
              child: const Text('设置壁纸'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('设置壁纸'));
    await tester.pumpAndSettle();
    expect(find.text('选择设置方式'), findsOneWidget);
    expect(find.text('动态壁纸'), findsOneWidget);
    expect(find.text('静态壁纸'), findsOneWidget);

    await tester.tap(find.text('静态壁纸'));
    await tester.pumpAndSettle();
    expect(selected, WallpaperEffect.staticImage);
  });

  testWidgets('同时包含4D和视频时默认预览4D并可切换', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        home: DetailScreen(repository: DualEffectDetailCatalog(), id: '1'),
      ),
    );
    await tester.pumpAndSettle();

    QjFilterChip chip(String label) =>
        tester.widget<QjFilterChip>(find.widgetWithText(QjFilterChip, label));
    expect(chip('4D壁纸').selected, isTrue);
    expect(chip('安卓动态壁纸').selected, isFalse);

    await tester.tap(find.text('安卓动态壁纸').first);
    await tester.pump();
    expect(chip('4D壁纸').selected, isFalse);
    expect(chip('安卓动态壁纸').selected, isTrue);
  });

  testWidgets('后台提供的四种资源按固定顺序显示并切换当前形式', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        home: DetailScreen(repository: MultiFormatDetailCatalog(), id: '1'),
      ),
    );
    await tester.pumpAndSettle();

    for (final label in const ['4D壁纸', '安卓动态壁纸', '苹果动态壁纸', '静态壁纸']) {
      expect(find.widgetWithText(QjFilterChip, label), findsOneWidget);
    }
    expect(
      tester
          .widget<QjFilterChip>(find.widgetWithText(QjFilterChip, '4D壁纸'))
          .selected,
      isTrue,
    );
    await tester.tap(find.text('苹果动态壁纸'));
    await tester.pump();
    expect(
      tester
          .widget<QjFilterChip>(find.widgetWithText(QjFilterChip, '苹果动态壁纸'))
          .selected,
      isTrue,
    );
  });

  testWidgets('详情主按钮同步显示当前资源下载进度', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final installer = _CurrentInstaller();
    final manager = DownloadManager(
      DeviceSessionManager(_UnusedTransport()),
      Uri.parse('https://example.test/api/v1'),
      installer: installer,
    );
    manager.value = const DownloadState(
      wallpaperId: '1',
      deliveryPlatform: 'UNIVERSAL',
      resourceType: 'STATIC_IMAGE',
      busy: true,
      status: 'downloading',
      received: 25,
      total: 100,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: DetailScreen(
          repository: DetailCatalog(),
          id: '1',
          downloads: manager,
          detailPreviewBuilder:
              (
                manager,
                wallpaperId,
                deliveryPlatform,
                resourceType,
                cover,
                active,
              ) => cover,
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('下载中 25%'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    manager.dispose();
    await installer.controller.close();
  });
}
