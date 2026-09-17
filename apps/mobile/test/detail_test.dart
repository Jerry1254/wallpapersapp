import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/detail/delivery.dart';
import 'package:qingjing_wallpaper/detail/detail_screen.dart';
import 'package:qingjing_wallpaper/downloads/download_panel.dart';
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

void main() {
  test('交付变体按平台及资源过滤，不从作品类型推导系统能力', () {
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
    expect(deliveryEffects(item, ClientPlatform.android), [
      WallpaperEffect.video,
      WallpaperEffect.staticImage,
    ]);
    expect(deliveryEffects(item, ClientPlatform.unknown), isEmpty);
    const capabilities = WallpaperCapabilities(
      platform: ClientPlatform.android,
    );
    expect(
      capabilities.canApply(WallpaperEffect.video, WallpaperTarget.lock),
      false,
    );
    expect(item.capabilityLabels, ['动态', '静态']);
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
    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNull);
    expect(find.text('当前设备不支持'), findsOneWidget);
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
}
