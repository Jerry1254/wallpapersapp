import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/privacy/policies.dart';
import 'package:qingjing_wallpaper/privacy/privacy_gate.dart';

void main() {
  testWidgets('未同意前不创建业务页，同意后保存当前政策版本', (tester) async {
    final store = MemoryPrivacyConsentStore();
    var businessBuilds = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: PrivacyGate(
          store: store,
          onReject: () {},
          builder: (_) {
            businessBuilds += 1;
            return const Scaffold(body: Text('业务首页'));
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(businessBuilds, 0);
    expect(find.text('隐私政策与用户协议'), findsOneWidget);
    expect(await store.acceptedVersion(), isNull);

    final policyLinks = tester
        .widgetList<TextButton>(find.byType(TextButton))
        .map((button) => ((button.child as Text).data))
        .toList();
    expect(policyLinks, ['《隐私政策》', '《用户协议》']);

    await tester.tap(find.text('《隐私政策》'));
    await tester.pumpAndSettle();
    expect(find.text('重要提示'), findsOneWidget);
    await tester.tap(find.byTooltip('返回'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('同意并继续'));
    await tester.pumpAndSettle();
    expect(await store.acceptedVersion(), policyVersion);
    expect(find.text('业务首页'), findsOneWidget);
    expect(businessBuilds, greaterThan(0));
  });

  testWidgets('旧版同意记录会重新弹出', (tester) async {
    final store = MemoryPrivacyConsentStore(accepted: 'older-version');
    await tester.pumpWidget(
      MaterialApp(
        home: PrivacyGate(
          store: store,
          onReject: () {},
          builder: (_) => const Text('业务首页'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('隐私政策与用户协议'), findsOneWidget);
    expect(find.text('业务首页'), findsNothing);
  });
}
