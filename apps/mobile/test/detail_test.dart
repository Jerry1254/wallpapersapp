import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/detail/delivery.dart';
import 'package:qingjing_wallpaper/detail/detail_screen.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'catalog_test.dart' show FakeCatalog;

Wallpaper wallpaper(List<Map<String, String>> capabilities) =>
    Wallpaper.fromJson({
      'id': '1',
      'title': '静态测试',
      'kind': 'STATIC',
      'cover': {'contentUrl': '/image'},
      'capabilities': capabilities,
    });

class DetailCatalog extends FakeCatalog {
  int attempts = 0;
  bool fail = false;
  @override
  Future<Wallpaper> detail(String id) async {
    attempts++;
    if (fail) throw const ApiFailure(404, 'WALLPAPER_NOT_FOUND');
    return wallpaper([
      {'platform': 'ANDROID', 'resourceType': 'STATIC_IMAGE'},
    ]);
  }
}

void main() {
  test('交付变体按平台及资源过滤，不从作品类型推导系统能力', () {
    final item = wallpaper([
      {'platform': 'IOS', 'resourceType': 'LIVE_PHOTO'},
      {'platform': 'ANDROID', 'resourceType': 'VIDEO'},
      {'platform': 'UNIVERSAL', 'resourceType': 'STATIC_IMAGE'},
      {'platform': 'ANDROID', 'resourceType': 'FUTURE'},
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
    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(repo.attempts, 2);
    expect(find.text('作品封面 · 非原生效果预览'), findsOneWidget);
    await tester.scrollUntilVisible(find.byType(FilledButton), 300);
    await tester.pumpAndSettle();
    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNull);
    expect(find.text('尚不可用'), findsNWidgets(3));
  });
}
