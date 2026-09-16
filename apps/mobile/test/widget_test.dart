import 'package:flutter/material.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'catalog_test.dart' show FakeCatalog;
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/config/app_config.dart';
import 'package:qingjing_wallpaper/main.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';

void main() {
  testWidgets('首页和我的两项导航可切换且不虚构内容', (tester) async {
    final repository = FakeCatalog();
    await tester.pumpWidget(
      QingjingApp(
        repository: repository,
        config: AppConfig(
          environment: 'local',
          apiBase: Uri.parse('http://127.0.0.1:8080/api/v1'),
          debug: true,
        ),
      ),
    );
    repository.pending.single.complete(WallpaperPage([], 1, 0));
    await tester.pumpAndSettle();
    expect(find.text('倾境'), findsOneWidget);
    expect(find.byType(TextField), findsOneWidget);
    expect(find.text('让每一屏，都有心动'), findsNothing);
    expect(find.text('查看分类'), findsNothing);
    expect(find.text('UI 规范'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('app-tab-1')));
    await tester.pump();
    expect(find.text('已获得壁纸'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('app-tab-0')));
    await tester.pumpAndSettle();
    expect(find.text('倾境'), findsOneWidget);
    expect(repository.pending, hasLength(1));
  });
  testWidgets('首页免费壁纸标签使用独立获取方式筛选', (tester) async {
    final repository = FakeCatalog();
    await tester.pumpWidget(
      QingjingApp(
        repository: repository,
        config: AppConfig(
          environment: 'local',
          apiBase: Uri.parse('http://127.0.0.1:8080/api/v1'),
          debug: true,
        ),
      ),
    );
    repository.pending.single.complete(WallpaperPage([], 1, 0));
    await tester.pumpAndSettle();
    await tester.tap(find.text('免费壁纸'));
    await tester.pump();
    expect(repository.queries.last['accessType'], 'FREE');
    repository.pending.last.complete(WallpaperPage([], 1, 0));
    await tester.pumpAndSettle();
  });
  test('正式包和非调试包拒绝明文地址', () {
    for (final env in ['local', 'prod']) {
      expect(
        () => AppConfig(
          environment: env,
          apiBase: Uri.parse('http://localhost/api/v1'),
          debug: false,
        ),
        throwsArgumentError,
      );
    }
    expect(
      () => AppConfig(
        environment: 'prod',
        apiBase: Uri.parse('http://localhost/api/v1'),
        debug: true,
      ),
      throwsArgumentError,
    );
  });
  test('未实现平台所有效果和操作显式不支持', () async {
    for (final platform in ClientPlatform.values) {
      final adapter = UnsupportedPlatform(platform);
      for (final effect in WallpaperEffect.values) {
        for (final target in WallpaperTarget.values) {
          expect(adapter.capabilities.canApply(effect, target), false);
          expect(
            (await adapter.apply('id', effect, target)).status,
            OperationStatus.unsupported,
          );
        }
      }
      expect((await adapter.publicKey()).status, OperationStatus.unsupported);
      expect((await adapter.install('id')).status, OperationStatus.unsupported);
      expect((await adapter.restore()).status, OperationStatus.unsupported);
    }
  });
}
