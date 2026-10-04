import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/catalog/catalog_image.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/detail/delivery.dart';
import 'package:qingjing_wallpaper/detail/detail_screen.dart';
import 'package:qingjing_wallpaper/design_system/qj_components.dart';
import 'package:qingjing_wallpaper/design_system/qj_theme.dart';
import 'package:qingjing_wallpaper/downloads/download_manager.dart';
import 'package:qingjing_wallpaper/downloads/download_panel.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'catalog_test.dart' show FakeCatalog;
import 'package:qingjing_wallpaper/entitlements/ios_acquisition.dart';
import 'ios_acquisition_test.dart' show TestAcquisitionApi, TestPurchaseStore;

Wallpaper wallpaper(
  List<Map<String, dynamic>> capabilities, {
  String title = '静态测试',
  bool free = false,
  int previewRevision = 0,
  String previewGenerationStatus = 'READY',
}) => Wallpaper.fromJson({
  'id': '1',
  'title': title,
  'accessType': free ? 'FREE' : 'PAID',
  'previewRevision': previewRevision,
  'previewGenerationStatus': previewGenerationStatus,
  'cover': {'contentUrl': '/image'},
  'availableCapabilities': capabilities,
});

class DetailCatalog extends FakeCatalog {
  bool free = false;
  int attempts = 0;
  bool fail = false;
  int previewRevision = 0;
  String previewGenerationStatus = 'READY';
  @override
  Future<Wallpaper> detail(String id) async {
    attempts++;
    if (fail) throw const ApiFailure(404, 'WALLPAPER_NOT_FOUND');
    return wallpaper(
      [
        {
          'deliveryPlatform': 'UNIVERSAL',
          'resourceType': 'STATIC_IMAGE',
          'placements': ['HOME'],
        },
      ],
      free: free,
      previewRevision: previewRevision,
      previewGenerationStatus: previewGenerationStatus,
    );
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
  ], title: '这是一个较长的壁纸标题用于验证详情页标题布局');
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
  testWidgets('预览重生成期间不复用旧封面，重试获取新资源版本', (tester) async {
    final repo = DetailCatalog()
      ..previewRevision = 7
      ..previewGenerationStatus = 'PROCESSING';
    await tester.pumpWidget(
      MaterialApp(
        home: DetailScreen(repository: repo, id: '1'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('预览资源生成中，请稍后'), findsOneWidget);
    expect(find.byType(CatalogImage), findsNothing);
    repo.previewGenerationStatus = 'READY';
    repo.previewRevision = 8;
    await tester.tap(find.text('重新加载预览'));
    await tester.pumpAndSettle();
    expect(repo.attempts, 2);
    expect(find.byType(CatalogImage), findsOneWidget);
    expect(find.text('预览资源生成中，请稍后'), findsNothing);
  });

  test('安卓安装包只展示安卓形式和通用静态，不用设备能力提前过滤', () {
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
      '动态壁纸',
      '静态壁纸',
    ]);
    expect(deliveryEffects(item, ClientPlatform.unknown), [
      WallpaperEffect.video,
      WallpaperEffect.staticImage,
    ]);
    expect(item.capabilityLabels, ['动态壁纸']);
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
    expect(chip('动态壁纸').selected, isFalse);
    final preview = find.byKey(const ValueKey('detail-preview'));
    final action = find.widgetWithText(QjPrimaryAction, '下载壁纸');
    final title = find.text('这是一个较长的壁纸标题用于验证详情页标题布局');
    expect(preview, findsOneWidget);
    expect(action, findsOneWidget);
    expect(title, findsOneWidget);
    expect(find.text('设置教程'), findsOneWidget);
    final previewRect = tester.getRect(preview);
    final actionRect = tester.getRect(action);
    final titleRect = tester.getRect(title);
    expect(previewRect.width, closeTo(350, 1));
    expect(titleRect.top, greaterThan(previewRect.bottom));
    expect(titleRect.top - previewRect.bottom, closeTo(16, 1));
    expect(tester.widget<Text>(title).maxLines, 2);
    expect(tester.widget<Text>(title).textAlign, TextAlign.left);
    expect(tester.widget<Text>(title).style?.color, isNot(T.colorAccentStrong));
    expect(find.text('壁纸详情'), findsOneWidget);
    expect(tester.getCenter(find.text('壁纸详情')).dx, closeTo(195, .01));
    expect(actionRect.top - titleRect.bottom, closeTo(16, 1));
    expect(actionRect.width, closeTo(350, 1));
    expect(actionRect.bottom, lessThanOrEqualTo(844));
    expect(
      find.descendant(of: action, matching: find.byType(Icon)),
      findsNothing,
    );
    expect(tester.takeException(), isNull);

    await tester.tap(find.widgetWithText(QjFilterChip, '动态壁纸'));
    await tester.pump();
    expect(chip('4D壁纸').selected, isFalse);
    expect(chip('动态壁纸').selected, isTrue);
  });

  testWidgets('安卓包过滤其他平台资源并按固定顺序切换当前形式', (tester) async {
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

    for (final label in const ['4D壁纸', '动态壁纸', '静态壁纸']) {
      expect(find.widgetWithText(QjFilterChip, label), findsOneWidget);
    }
    expect(find.widgetWithText(QjFilterChip, '苹果动态壁纸'), findsNothing);
    expect(
      tester
          .widget<QjFilterChip>(find.widgetWithText(QjFilterChip, '4D壁纸'))
          .selected,
      isTrue,
    );
    await tester.tap(find.widgetWithText(QjFilterChip, '动态壁纸'));
    await tester.pump();
    expect(
      tester
          .widget<QjFilterChip>(find.widgetWithText(QjFilterChip, '动态壁纸'))
          .selected,
      isTrue,
    );
  });

  testWidgets('小屏单类型不显示Tab，图片填充剩余空间，全屏返回保留预览', (tester) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final installer = _CurrentInstaller();
    final manager = DownloadManager(
      DeviceSessionManager(_UnusedTransport()),
      Uri.parse('https://example.test/api/v1'),
      installer: installer,
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        home: DetailScreen(
          repository: DetailCatalog()..free = true,
          id: '1',
          downloads: manager,
          detailPreviewBuilder: (_, _, _, _, cover, _) => cover,
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(QjFilterChip), findsNothing);
    final before = tester.getRect(find.byKey(const ValueKey('detail-preview')));
    expect(tester.getCenter(find.text('壁纸详情')).dx, closeTo(160, .01));
    expect(before.width, 280);
    expect(before.height, greaterThan(300));
    expect(
      tester.getBottomLeft(find.byType(QjPrimaryAction)).dy,
      lessThanOrEqualTo(568),
    );
    final imageElement = tester.element(find.byType(CatalogImage));
    await tester.tap(find.byTooltip('全屏预览'));
    await tester.pumpAndSettle();
    final full = tester.getRect(find.byKey(const ValueKey('detail-preview')));
    expect(full.size, const Size(320, 568));
    expect(find.text('静态测试'), findsNothing);
    expect(find.byType(QjPrimaryAction), findsNothing);
    expect(tester.element(find.byType(CatalogImage)), same(imageElement));
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('静态测试'), findsOneWidget);
    expect(
      tester.getRect(find.byKey(const ValueKey('detail-preview'))),
      before,
    );
    expect(tester.element(find.byType(CatalogImage)), same(imageElement));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
    manager.dispose();
    await installer.controller.close();
  });

  testWidgets('iPhone标题以页面居中，Tab与顶部栏仅留2点间距', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final flow = IosAcquisitionController(
      TestAcquisitionApi(),
      TestPurchaseStore(),
    );
    addTearDown(flow.dispose);
    await flow.initialize();
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        home: DetailScreen(
          repository: DualEffectDetailCatalog(),
          id: '1',
          iosAcquisition: flow,
        ),
      ),
    );
    await tester.pumpAndSettle();
    final titleRect = tester.getRect(find.text('壁纸详情'));
    final tutorialRect = tester.getRect(
      find.widgetWithText(TextButton, '设置教程'),
    );
    final headerRect = tester.getRect(
      find.byKey(const ValueKey('detail-header')),
    );
    expect(titleRect.center.dx, closeTo(195, .01));
    expect(titleRect.right, lessThan(tutorialRect.left));
    expect(
      titleRect.left,
      greaterThan(tester.getRect(find.byTooltip('返回')).right),
    );
    expect(
      tester.getRect(find.widgetWithText(QjFilterChip, '4D壁纸')).top -
          headerRect.bottom,
      2,
    );
    expect(find.byTooltip('全屏预览'), findsOneWidget);
    expect(tester.takeException(), isNull);
    tester.view.physicalSize = const Size(320, 568);
    await tester.pumpAndSettle();
    expect(tester.getCenter(find.text('壁纸详情')).dx, closeTo(160, .01));
    expect(
      tester.getRect(find.text('壁纸详情')).right,
      lessThan(tester.getRect(find.widgetWithText(TextButton, '设置教程')).left),
    );
    expect(tester.takeException(), isNull);
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
