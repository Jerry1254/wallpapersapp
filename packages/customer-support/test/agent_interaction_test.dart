import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_customer_support/customer_support.dart';
import 'chat_test.dart' show MemoryStore;

void main() {
  testWidgets('客服切换用户保留草稿，后台短语插入后明确发送，在线状态持续更新', (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final font = Platform.environment['SUPPORT_QA_FONT'];
    if (font != null) {
      await tester.runAsync(() async {
        final loader = FontLoader('SupportQA');
        loader.addFont(File(font).readAsBytes().then(ByteData.sublistView));
        await loader.load();
        final icons = Platform.environment['SUPPORT_QA_ICONS'];
        if (icons != null) {
          final iconLoader = FontLoader('MaterialIcons');
          iconLoader.addFont(
            File(icons).readAsBytes().then(ByteData.sublistView),
          );
          await iconLoader.load();
        }
      });
    }
    var online = true;
    final sends = <SupportData>[];
    final api = EndpointSupportApi(
      agent: true,
      base: Uri.parse('https://example.test/api/v1'),
      scope: () async => 'test',
      headers: () async => {},
      request: (path, method, body) async {
        if (path.contains('/library?')) {
          return {
            'items': [
              {
                'id': '3',
                'kind': 'PHRASE',
                'title': '设置帮助',
                'note': '后台同步',
                'text': '请打开详情，点击设置壁纸。',
                'attachment': null,
              },
            ],
            'total': 1,
          };
        }
        if (path.contains('/conversations?')) {
          return {
            'items': [
              for (final id in ['1', '2'])
                {
                  'id': id,
                  'name': id == '1' ? '用户 0291' : '用户 0862',
                  'online': online,
                  'unreadCount': 1,
                  'lastMessage': {
                    'kind': 'TEXT',
                    'text': '壁纸怎么设置？',
                    'id': '10',
                    'sender': 'CUSTOMER',
                  },
                },
            ],
            'total': 2,
          };
        }
        final cid = path
            .split('/conversations/')
            .last
            .split('/')
            .first
            .split('?')
            .first;
        if (path.endsWith('/messages') && method == 'POST') {
          sends.add({'cid': cid, ...body!});
          return {
            ...body,
            'id': '20',
            'conversationId': cid,
            'sender': 'ADMIN',
            'attachment': null,
            'createdAt': '2026-10-09T01:00:00Z',
          };
        }
        if (path.contains('/messages?')) {
          return {'items': <dynamic>[], 'cursor': '0', 'hasMore': false};
        }
        return {'online': online};
      },
    );
    final chat = SupportChat(api, MemoryStore());
    await chat.initialize();
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme: ThemeData(
            fontFamily: font == null ? null : 'SupportQA',
            colorScheme: ColorScheme.fromSeed(
              seedColor: const Color(0xfff3a81e),
              primary: const Color(0xffbd7900),
              surface: Colors.white,
            ),
            scaffoldBackgroundColor: const Color(0xfffaf9f6),
          ),
          home: SupportAgentScreen(
            chat: chat,
            logout: () async {},
            notifySound: () {},
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump();
    expect(find.text('用户 0291'), findsOneWidget);
    await capture(tester, boundary, 'support-agent-list');
    await tester.tap(find.text('用户 0291'));
    await tester.pumpAndSettle();
    expect(find.text('● 在线'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '用户一的草稿');
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.text('用户 0862'));
    await tester.pumpAndSettle();
    expect(
      (tester.widget<TextField>(find.byType(TextField))).controller!.text,
      '',
    );
    await tester.enterText(find.byType(TextField), '用户二的草稿');
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.text('用户 0291'));
    await tester.pumpAndSettle();
    expect(
      (tester.widget<TextField>(find.byType(TextField))).controller!.text,
      '用户一的草稿',
    );
    await tester.enterText(find.byType(TextField), '');
    await tester.tap(find.text('快捷短语'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('插入短语'));
    await tester.pumpAndSettle();
    expect(sends, isEmpty);
    expect(
      (tester.widget<TextField>(find.byType(TextField))).controller!.text,
      '请打开详情，点击设置壁纸。',
    );
    await tester.tap(find.text('发送'));
    await tester.pumpAndSettle();
    expect(sends.single['cid'], '1');
    expect(find.text('已发送'), findsOneWidget);
    online = false;
    await tester.pump(const Duration(seconds: 2));
    await tester.pump();
    await tester.pump();
    expect(find.text('● 离线'), findsOneWidget);
    expect(chat.draft('2')['text'], '用户二的草稿');
    expect(tester.takeException(), isNull);
    await capture(tester, boundary, 'support-agent-chat');
    await tester.pumpWidget(const SizedBox());
    chat.dispose();
  });
}

Future<void> capture(
  WidgetTester tester,
  GlobalKey boundary,
  String name,
) async {
  final directory = Platform.environment['SUPPORT_QA_OUTPUT'];
  if (directory == null) return;
  await tester.runAsync(() async {
    final image =
        await (boundary.currentContext!.findRenderObject()!
                as RenderRepaintBoundary)
            .toImage();
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    await File(
      '$directory/$name.png',
    ).writeAsBytes(bytes!.buffer.asUint8List());
    image.dispose();
  });
}
