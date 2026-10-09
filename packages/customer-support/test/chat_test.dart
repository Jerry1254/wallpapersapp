import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_customer_support/customer_support.dart';

class MemoryStore implements SupportStore {
  SupportData value = {'pending': <dynamic>[], 'drafts': <String, dynamic>{}};
  bool fail = false;
  @override
  Future<SupportData> load() async =>
      jsonDecode(jsonEncode(value)) as SupportData;
  @override
  Future<void> save(SupportData data) async {
    if (fail) throw const FileSystemException('full');
    value = jsonDecode(jsonEncode(data)) as SupportData;
  }
}

SupportData message(
  SupportData body, {
  String cid = '1',
  String id = '11',
  String sender = 'ADMIN',
}) => {
  ...body,
  'id': id,
  'conversationId': cid,
  'sender': sender,
  'attachment': null,
  'createdAt': DateTime.now().toUtc().toIso8601String(),
};
void main() {
  test('原请求先持久化；存储失败不发送或清空草稿', () async {
    var count = 0;
    final store = MemoryStore();
    final api = EndpointSupportApi(
      agent: true,
      base: Uri.parse('https://example.test/api/v1'),
      headers: () async => {},
      scope: () async => 'test',
      request: (path, method, body) async {
        count++;
        return message(body!);
      },
    );
    final chat = SupportChat(api, store);
    await chat.initialize();
    chat.draft('1')['text'] = '你好';
    store.fail = true;
    await expectLater(chat.sendDraft('1'), throwsA(isA<FileSystemException>()));
    expect(count, 0);
    expect(chat.draft('1')['text'], '你好');
    expect(chat.pending, isEmpty);
    chat.dispose();
  });
  test('超时后重启核对成功不会重复发送；标识和消息内容保留', () async {
    var sends = 0;
    SupportData? saved;
    final store = MemoryStore();
    final api = EndpointSupportApi(
      agent: true,
      base: Uri.parse('https://example.test/api/v1'),
      headers: () async => {},
      scope: () async => 'test',
      request: (path, method, body) async {
        if (method == 'POST') {
          sends++;
          expect(store.value['pending'], isNotEmpty);
          saved = message(body!);
          throw const SupportFailure(0, 'TIMEOUT', '发送结果未确认');
        }
        return saved!;
      },
    );
    final chat = SupportChat(api, store);
    await chat.initialize();
    chat.draft('1')['text'] = '你好';
    await chat.sendDraft('1');
    expect(chat.pending.single['state'], 'uncertain');
    final restored = SupportChat(api, store);
    await restored.initialize();
    await restored.recover('1');
    expect(restored.pending, isEmpty);
    expect(restored.feed('1').messages.single['text'], '你好');
    expect(sends, 1);
    chat.dispose();
    restored.dispose();
  });
  test('确认未保存才手动重发，同 UUID 用户消息不能消除客服发送记录', () async {
    var sends = 0;
    final ids = <String>[];
    final store = MemoryStore();
    final api = EndpointSupportApi(
      agent: true,
      base: Uri.parse('https://example.test/api/v1'),
      headers: () async => {},
      scope: () async => 'test',
      request: (path, method, body) async {
        if (method == 'GET')
          throw const SupportFailure(404, 'SUPPORT_MESSAGE_NOT_FOUND', '未找到');
        sends++;
        ids.add(body!['clientId'] as String);
        if (sends == 1) throw const SupportFailure(400, 'INVALID', '发送失败');
        return message(body);
      },
    );
    final chat = SupportChat(api, store);
    await chat.initialize();
    chat.draft('1')['text'] = '你好';
    await chat.sendDraft('1');
    final pending = chat.pending.single;
    await chat.merge('1', [
      message(
        Map<String, dynamic>.from(pending['request'] as Map),
        sender: 'CUSTOMER',
      ),
    ]);
    expect(chat.pending, hasLength(1));
    await chat.attempt(pending, true);
    expect(ids.toSet(), hasLength(1));
    expect(sends, 2);
    expect(chat.pending, isEmpty);
    chat.dispose();
  });
  test('发送完成不推进收取位置，也不清掉还未同步的未读', () async {
    final calls = <String>[];
    final store = MemoryStore();
    final api = EndpointSupportApi(
      agent: true,
      base: Uri.parse('https://example.test/api/v1'),
      headers: () async => {},
      scope: () async => 'test',
      request: (path, method, body) async {
        calls.add(path);
        if (method == 'POST') return message(body!, id: '99');
        return {'items': <dynamic>[], 'cursor': '0', 'hasMore': false};
      },
    );
    final chat = SupportChat(api, store);
    await chat.initialize();
    await chat.open('1');
    chat.draft('1')['text'] = '你好';
    await chat.sendDraft('1');
    await chat.markRead('1', '99');
    expect(chat.feed('1').since, '0');
    expect(calls.where((p) => p.endsWith('/read')), isEmpty);
    await chat.sync('1');
    expect(calls.last, contains('after=0'));
    chat.dispose();
  });
  test('各会话草稿独立，媒体发送保留文字；迟到结果仍归属原会话', () async {
    final done = Completer<SupportData>();
    SupportData? body;
    final store = MemoryStore();
    final api = EndpointSupportApi(
      agent: true,
      base: Uri.parse('https://example.test/api/v1'),
      headers: () async => {},
      scope: () async => 'test',
      request: (path, method, data) async {
        body = data;
        return done.future;
      },
    );
    final chat = SupportChat(api, store);
    await chat.initialize();
    chat.draft('1')['text'] = '原用户草稿';
    chat.draft('2')['text'] = '另一个用户';
    final sending = chat.sendDraft('1');
    await Future<void>.delayed(Duration.zero);
    done.complete(message(body!));
    await sending;
    expect(chat.feed('1').messages, hasLength(1));
    expect(chat.feed('2').messages, isEmpty);
    expect(chat.draft('2')['text'], '另一个用户');
    chat.dispose();
  });
  test('原子文件保存可恢复发送记录，并且序列化并发写入', () async {
    final dir = await Directory.systemTemp.createTemp('support-store-');
    try {
      final file = File('${dir.path}/outbox.json');
      final store = FileSupportStore(file);
      await Future.wait([
        store.save({
          'pending': [1],
          'drafts': {},
        }),
        store.save({
          'pending': [2],
          'drafts': {},
        }),
      ]);
      expect((await store.load())['pending'], [2]);
      expect(await File('${file.path}.next').exists(), false);
    } finally {
      await dir.delete(recursive: true);
    }
  });
  test('媒体地址限定到授权附件，不能加载外部地址', () async {
    final api = EndpointSupportApi(
      agent: false,
      base: Uri.parse('https://example.test/api/v1'),
      headers: () async => {},
      scope: () async => 'test',
      request: (path, method, body) async => {
        'url': 'https://other.test/file',
        'expiresAt': '2099-01-01T00:00:00Z',
      },
    );
    await expectLater(api.media('1'), throwsA(isA<SupportFailure>()));
  });
  test('读取确认合并在途请求；素材单独发送并保留文字', () async {
    final complete = Completer<SupportData>();
    var readCalls = 0;
    final asset = <String, dynamic>{
      'id': '7',
      'kind': 'IMAGE',
      'filename': '说明.png',
    };
    final api = EndpointSupportApi(
      agent: true,
      base: Uri.parse('https://example.test/api/v1'),
      headers: () async => {},
      scope: () async => 'test',
      request: (path, method, body) async {
        if (path.endsWith('/read')) {
          readCalls++;
          return complete.future;
        }
        return {...message(body!), 'attachment': asset};
      },
    );
    final chat = SupportChat(api, MemoryStore());
    await chat.initialize();
    chat.feed('1').since = '10';
    final first = chat.markRead('1', '10');
    await chat.markRead('1', '10');
    expect(readCalls, 1);
    complete.complete({});
    await first;
    await chat.markRead('1', '10');
    expect(readCalls, 1);
    chat.draft('1').addAll({
      'text': '还没发送的文字',
      'attachment': asset,
      'libraryItemId': '8',
    });
    await chat.sendDraft('1');
    expect(chat.pending, isEmpty);
    expect(chat.feed('1').messages.single['kind'], 'IMAGE');
    expect(chat.draft('1')['text'], '还没发送的文字');
    chat.dispose();
  });
  test('损坏的本机发送记录不能被草稿或核对过程覆盖', () async {
    final dir = await Directory.systemTemp.createTemp('support-corrupt-');
    try {
      final file = File('${dir.path}/outbox.json');
      await file.writeAsString('broken-json');
      final api = EndpointSupportApi(
        agent: false,
        base: Uri.parse('https://example.test/api/v1'),
        headers: () async => {},
        scope: () async => 'test',
        request: (path, method, body) async => {},
      );
      final chat = SupportChat(api, FileSupportStore(file));
      await chat.initialize();
      chat.draft('1')['text'] = '新草稿';
      await chat.saveDraft();
      await expectLater(chat.sendDraft('1'), throwsA(isA<SupportFailure>()));
      expect(await file.readAsString(), 'broken-json');
      chat.dispose();
    } finally {
      await dir.delete(recursive: true);
    }
  });
  testWidgets('用户聊天无客服在线状态；文字发送显示服务端已保存', (tester) async {
    final store = MemoryStore();
    final api = EndpointSupportApi(
      agent: false,
      base: Uri.parse('https://example.test/api/v1'),
      headers: () async => {},
      scope: () async => 'test',
      request: (path, method, body) async {
        if (path.endsWith('/messages') && method == 'POST')
          return message(body!, sender: 'CUSTOMER');
        if (path.contains('/messages?'))
          return {'items': <dynamic>[], 'cursor': '0', 'hasMore': false};
        return {'ok': true};
      },
    );
    final chat = SupportChat(api, store);
    await chat.initialize();
    await tester.pumpWidget(
      MaterialApp(
        home: SupportChatScreen(chat: chat, conversationId: '1', title: '在线客服'),
      ),
    );
    await tester.pump();
    await tester.pump();
    expect(find.text('● 在线'), findsNothing);
    expect(find.text('● 离线'), findsNothing);
    await tester.enterText(find.byType(TextField), '我想问一个问题');
    await tester.pump();
    await tester.tap(find.text('发送'));
    await tester.pump();
    await tester.pump();
    expect(find.text('已发送'), findsOneWidget);
    expect(chat.feed('1').messages.single['text'], '我想问一个问题');
    await tester.pumpWidget(const SizedBox());
    chat.dispose();
  });
}
