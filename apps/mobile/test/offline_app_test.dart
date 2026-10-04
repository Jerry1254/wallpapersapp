import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/catalog/catalog_image.dart';
import 'package:qingjing_wallpaper/config/app_branding.dart';
import 'package:qingjing_wallpaper/config/app_config.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/main.dart';
import 'package:qingjing_wallpaper/privacy/privacy_gate.dart';
import 'catalog_test.dart' show FakeCatalog;
import 'widget_test.dart' show noUpdates;

class _UnusedTransport implements DeviceTransport {
  @override
  Future<Map<String, dynamic>> request(
    String path, {
    String method = 'GET',
    String? body,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) => throw StateError('No transport request expected');
}

class _MediaSessions extends DeviceSessionManager {
  _MediaSessions() : super(_UnusedTransport());
  int calls = 0;
  bool fail = false;

  @override
  Future<DeviceSession> session() async {
    calls++;
    if (fail) throw const DeviceApiError(401, 'SESSION_INVALID');
    return DeviceSession(
      'test-only-token',
      DateTime.now().add(const Duration(hours: 1)),
      'test-only-credential',
    );
  }
}

AppConfig offlineConfig() => AppConfig(
  environment: 'offline',
  apiBase: Uri.parse('https://example.com/api/v1'),
  debug: false,
);

void main() {
  test('线下品牌使用正式网络环境且不能启用明文 API', () {
    final config = offlineConfig();
    expect(config.environment, 'prod');
    expect(config.isOffline, isTrue);
    expect(config.branding.appName, '吉意壁纸');
    final prod = AppConfig(
      environment: 'prod',
      apiBase: config.apiBase,
      debug: false,
    );
    expect(prod.branding, AppBranding.qingjing);
    expect(prod.isOffline, isFalse);
    expect(
      () => AppConfig(
        environment: 'offline',
        apiBase: Uri.parse('http://example.com/api/v1'),
        debug: true,
      ),
      throwsArgumentError,
    );
  });

  testWidgets('吉意首启与首页显示独立品牌，协议正文沿用相同服务规则', (tester) async {
    final repository = FakeCatalog();
    await tester.pumpWidget(
      QingjingApp(
        config: offlineConfig(),
        repository: repository,
        updateController: noUpdates(),
        privacyConsentStore: MemoryPrivacyConsentStore(),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('吉意'), findsOneWidget);
    expect(find.textContaining('欢迎使用吉意壁纸'), findsOneWidget);
    expect(repository.pending, isEmpty);
    await tester.tap(find.text('《用户协议》'));
    await tester.pumpAndSettle();
    expect(find.textContaining('欢迎使用吉意壁纸。本协议'), findsOneWidget);
    expect(find.textContaining('欢迎使用倾境动态壁纸'), findsNothing);
    tester.state<NavigatorState>(find.byType(Navigator)).pop();
    await tester.pumpAndSettle();
    await tester.tap(find.text('同意并继续'));
    await tester.pump();
    repository.pending.single.complete(WallpaperPage([], 1, 0));
    await tester.pumpAndSettle();
    expect(find.text('吉意'), findsOneWidget);
    expect(find.text('倾境'), findsNothing);
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).title, '吉意壁纸');
  });

  test('线下封面带设备会话，外部 URL 不泄露会话，线上保留公共请求', () async {
    final sessions = _MediaSessions();
    final repository = HttpCatalogRepository(
      Uri.parse('https://example.com/api/v1'),
      sessions: sessions,
      authenticatedMedia: true,
    );
    expect(
      await repository.mediaHeaders(
        repository.media('/api/v1/wallpapers/1/cover?revision=2'),
      ),
      {'Authorization': 'Bearer test-only-token'},
    );
    expect(
      await repository.mediaHeaders(
        Uri.parse('https://external.example/cover'),
      ),
      isEmpty,
    );
    expect(sessions.calls, 1);
    final online = HttpCatalogRepository(repository.base, sessions: sessions);
    expect(
      await online.mediaHeaders(online.media('/api/v1/wallpapers/1/cover')),
      isEmpty,
    );
    expect(sessions.calls, 1);
  });

  testWidgets('线下封面会话失败不会匿名获取图片或显示旧图', (tester) async {
    final sessions = _MediaSessions()..fail = true;
    await tester.pumpWidget(
      MaterialApp(
        home: CatalogImage(
          repository: HttpCatalogRepository(
            Uri.parse('https://example.com/api/v1'),
            sessions: sessions,
            authenticatedMedia: true,
          ),
          path: '/api/v1/wallpapers/1/cover?revision=2',
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(sessions.calls, 1);
    expect(find.byType(Image), findsNothing);
    expect(find.byIcon(Icons.image_outlined), findsOneWidget);
  });
}
