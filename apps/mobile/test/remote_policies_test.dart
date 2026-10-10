import 'dart:convert';
import 'dart:io';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/config/app_config.dart';
import 'package:qingjing_wallpaper/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/privacy/remote_policies.dart';
import 'package:qingjing_wallpaper/privacy/policies.dart';
import 'package:qingjing_wallpaper/privacy/privacy_gate.dart';
import 'catalog_test.dart' show FakeCatalog;
import 'widget_test.dart' show noUpdates;

String manifest({int revision = 1, int consent = 1}) => jsonEncode({
  'consentVersion': 'privacy:$consent|terms:1',
  'items': [
    for (final key in ['privacy', 'terms'])
      {
        'key': key,
        'revision': revision,
        'consentRevision': key == 'privacy' ? consent : 1,
        'content': {
          'title': key == 'privacy' ? '远程隐私政策' : '远程用户协议',
          'effectiveDate': '2026年10月9日',
          'introduction': '后台发布的说明',
          'sections': [
            {'title': '远程章节', 'body': '最新正文'},
          ],
        },
      },
  ],
});

class FixedSource implements PolicySource {
  FixedSource(this.policies);
  final PublishedPolicies policies;
  @override
  Future<PublishedPolicies> load() async => policies;
}

class RealHttpOverrides extends HttpOverrides {}

class UpdatingSource implements PolicySource {
  UpdatingSource(this.current);
  PublishedPolicies current;
  int calls = 0;
  @override
  Future<PublishedPolicies> load() async {
    calls++;
    return current;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('qingjing/privacy_consent');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  test('拒绝缺失、版本不一致或空白的协议', () {
    expect(PublishedPolicies.parse(manifest()).privacy.title, '远程隐私政策');
    expect(
      () => PublishedPolicies.parse(
        manifest().replaceFirst('privacy:1|terms:1', 'privacy:2|terms:1'),
      ),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => PublishedPolicies.parse(manifest().replaceFirst('最新正文', ' ')),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => PublishedPolicies.parse('{"items":[]}'),
      throwsA(isA<FormatException>()),
    );
  });
  test('匿名请求无设备身份，保存有效缓存；错误响应回退同源缓存', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    var fail = false;
    server.listen((request) async {
      expect(request.uri.path, '/api/v1/public/legal-documents');
      expect(request.headers.value('Authorization'), isNull);
      expect(request.headers.value('Cookie'), isNull);
      expect(request.headers.value('X-Device-Id'), isNull);
      expect(request.headers.value('X-App-Version'), isNull);
      request.response.statusCode = fail ? 500 : 200;
      request.response.write(fail ? '{}' : manifest(revision: 2));
      await request.response.close();
    });
    String? cached;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'cachedPolicies') return cached;
          if (call.method == 'savePolicies') cached = call.arguments as String;
          return null;
        });
    final source = RemotePolicySource(
      Uri.parse('http://127.0.0.1:${server.port}/api/v1'),
      createClient: () => RealHttpOverrides().createHttpClient(null),
    );
    expect((await source.load()).privacy.title, '远程隐私政策');
    expect(cached, isNotNull);
    fail = true;
    expect((await source.load()).privacy.title, '远程隐私政策');
    cached = jsonEncode({
      'apiBase': 'https://different.invalid/api/v1',
      'body': manifest(),
    });
    expect(await source.load(), PublishedPolicies.bundled);
  });
  testWidgets('普通发布无需重同意，协议中心展示已发布内容', (tester) async {
    final current = PublishedPolicies.parse(manifest(revision: 2));
    await tester.pumpWidget(
      MaterialApp(
        home: PrivacyGate(
          store: MemoryPrivacyConsentStore(accepted: current.consentVersion),
          policySource: FixedSource(current),
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) =>
                      PolicyCenterScreen(policies: PolicyScope.of(context)),
                ),
              ),
              child: const Text('协议入口'),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('同意并继续'), findsNothing);
    await tester.tap(find.text('协议入口'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('远程隐私政策'));
    await tester.pumpAndSettle();
    expect(find.text('最新正文'), findsOneWidget);
  });
  testWidgets('我的顶部协议入口重新获取后台，两份协议同步且不改同意记录', (tester) async {
    const identityChannel = MethodChannel('qingjing/wallpaper_android');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(identityChannel, (call) async {
      if (call.method == 'identity') {
        throw PlatformException(code: 'TEST_NO_DEVICE');
      }
      return null;
    });
    addTearDown(
      () => messenger.setMockMethodCallHandler(identityChannel, null),
    );
    final source = UpdatingSource(PublishedPolicies.parse(manifest()));
    final store = MemoryPrivacyConsentStore(
      accepted: source.current.consentVersion,
    );
    final catalog = FakeCatalog();
    await tester.pumpWidget(
      QingjingApp(
        policySource: source,
        privacyConsentStore: store,
        repository: catalog,
        updateController: noUpdates(),
        config: AppConfig(
          environment: 'local',
          apiBase: Uri.parse('http://127.0.0.1:8080/api/v1'),
          debug: true,
        ),
      ),
    );
    await tester.pump();
    catalog.pending.single.complete(WallpaperPage([], 1, 0));
    await tester.pumpAndSettle();
    expect(source.calls, 1);
    source.current = PublishedPolicies.parse(
      manifest(
        revision: 2,
        consent: 2,
      ).replaceAll('最新正文', '后台重新发布正文').replaceAll('2026年10月9日', '2026年10月10日'),
    );
    await tester.tap(find.byKey(const ValueKey('app-tab-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('用户协议与隐私政策'));
    await tester.pumpAndSettle();
    expect(source.calls, 2);
    expect(find.text('生效日期：2026年10月10日'), findsOneWidget);
    expect(
      find.text('生效日期：2026年10月10日（本版本补充：$policyEffectiveDate）'),
      findsOneWidget,
    );
    for (final title in ['远程隐私政策', '远程用户协议']) {
      await tester.tap(find.text(title));
      await tester.pumpAndSettle();
      expect(find.text('后台重新发布正文'), findsOneWidget);
      await tester.tap(find.byTooltip('返回'));
      await tester.pumpAndSettle();
    }
    expect(
      await store.acceptedVersion(),
      PublishedPolicies.parse(manifest()).consentVersion,
    );
    await tester.tap(find.byTooltip('返回'));
    await tester.pumpAndSettle();
    source.current = PublishedPolicies.parse(
      manifest(revision: 3).replaceAll('最新正文', '再次发布正文'),
    );
    await tester.tap(find.text('用户协议与隐私政策'));
    await tester.pumpAndSettle();
    expect(source.calls, 3);
    await tester.tap(find.text('远程隐私政策'));
    await tester.pumpAndSettle();
    expect(find.text('再次发布正文'), findsOneWidget);
  });
  testWidgets('重大发布先拦截业务，同意保存当前快照版本', (tester) async {
    final current = PublishedPolicies.parse(manifest(revision: 3, consent: 2));
    final store = MemoryPrivacyConsentStore(
      accepted: PublishedPolicies.parse(manifest()).consentVersion,
    );
    var started = false;
    await tester.pumpWidget(
      MaterialApp(
        home: PrivacyGate(
          store: store,
          policySource: FixedSource(current),
          builder: (_) {
            started = true;
            return const Scaffold(body: Text('业务首页'));
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(started, isFalse);
    await tester.tap(find.text('《隐私政策》'));
    await tester.pumpAndSettle();
    expect(find.text('最新正文'), findsOneWidget);
    await tester.tap(find.byTooltip('返回'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('同意并继续'));
    await tester.pumpAndSettle();
    expect(
      await store.acceptedVersion(),
      'privacy:2|terms:1|app-privacy:$policyVersion',
    );
    expect(started, isTrue);
  });

  testWidgets('服务端仍发布旧协议时先重新告知当前版本，旧同意不能启动业务', (tester) async {
    final current = PublishedPolicies.parse(manifest());
    expect(current.privacy.sections.last, currentAppPrivacySupplement);
    final store = MemoryPrivacyConsentStore(accepted: 'privacy:1|terms:1');
    var started = false;
    await tester.pumpWidget(
      MaterialApp(
        home: PrivacyGate(
          store: store,
          policySource: FixedSource(current),
          builder: (_) {
            started = true;
            return const Text('业务首页');
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(started, isFalse);
    await tester.tap(find.text('《隐私政策》'));
    await tester.pumpAndSettle();
    expect(find.text(currentAppPrivacySupplement.title), findsOneWidget);
    await tester.tap(find.byTooltip('返回'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('同意并继续'));
    await tester.pumpAndSettle();
    expect(await store.acceptedVersion(), current.consentVersion);
    expect(started, isTrue);
  });

  test('已发布补充说明被规范化一次，缓存或远程不能删除本版本告知', () {
    final root = jsonDecode(manifest()) as Map<String, dynamic>;
    final items = root['items'] as List<dynamic>;
    final sections = items.first['content']['sections'] as List<dynamic>;
    sections.add({'title': currentAppPrivacySupplement.title, 'body': '旧补充'});
    final current = PublishedPolicies.parse(jsonEncode(root));
    expect(
      current.privacy.sections.where(
        (s) => s.title == currentAppPrivacySupplement.title,
      ),
      [currentAppPrivacySupplement],
    );
    expect(current.privacy.sections.first.body, '最新正文');
  });
}
