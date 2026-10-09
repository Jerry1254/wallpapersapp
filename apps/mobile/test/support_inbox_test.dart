import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_customer_support/customer_support.dart';
import 'package:qingjing_wallpaper/support/inbox.dart';

void main() {
  testWidgets('同意业务入口启动前不请求客服；前台未读提示，进入后台暂停', (tester) async {
    var calls = 0;
    final api = EndpointSupportApi(
      agent: false,
      base: Uri.parse('https://example.test/api/v1'),
      scope: () async => 'test',
      headers: () async => {},
      request: (path, method, body) async {
        calls++;
        return {'unreadCount': calls == 1 ? 2 : 0};
      },
    );
    final inbox = CustomerSupportInbox(api);
    await tester.pumpWidget(
      CustomerSupportScope(
        api: api,
        inbox: inbox,
        child: const MaterialApp(
          home: Scaffold(body: CustomerSupportUnreadBadge(child: Text('客服'))),
        ),
      ),
    );
    await tester.pump();
    expect(calls, 0);
    inbox.start();
    await tester.pump();
    await tester.pump();
    expect(find.text('2'), findsOneWidget);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 5));
    expect(calls, 1);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(calls, 2);
    expect(inbox.unread, 0);
    await tester.pumpWidget(const SizedBox());
    inbox.dispose();
  });
}
