import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/privacy/remote_policies.dart';
import 'package:qingjing_wallpaper/privacy/privacy_gate.dart';

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
          store: MemoryPrivacyConsentStore(accepted: 'privacy:1|terms:1'),
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
  testWidgets('重大发布先拦截业务，同意保存当前快照版本', (tester) async {
    final current = PublishedPolicies.parse(manifest(revision: 3, consent: 2));
    final store = MemoryPrivacyConsentStore(accepted: 'privacy:1|terms:1');
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
    expect(await store.acceptedVersion(), 'privacy:2|terms:1');
    expect(started, isTrue);
  });
}
