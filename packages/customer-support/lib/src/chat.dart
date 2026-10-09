import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'api.dart';
import 'storage.dart';

class SupportFeed {
  bool reading = false;
  List<SupportData> messages = [];
  String since = '0', older = '0', readThrough = '0';
  bool initialized = false, hasOlder = false;
}

class SupportChat extends ChangeNotifier {
  SupportChat(this.api, this.store);
  final SupportApi api;
  final SupportStore store;
  final Map<String, SupportFeed> feeds = {};
  Map<String, dynamic> drafts = {};
  List<SupportData> pending = [];
  String storageError = '';
  bool _disposed = false;
  bool _unreadable = false;
  final Set<String> busy = {};
  SupportFeed feed(String id) => feeds.putIfAbsent(id, SupportFeed.new);
  SupportData draft(String id) =>
      drafts.putIfAbsent(
            id,
            () => <String, dynamic>{
              'text': '',
              'attachment': null,
              'libraryItemId': null,
            },
          )
          as SupportData;
  void changed() {
    if (!_disposed) notifyListeners();
  }

  SupportData snapshot() =>
      jsonDecode(jsonEncode({'pending': pending, 'drafts': drafts}))
          as SupportData;
  Future<void> initialize() async {
    try {
      final data = await store.load();
      pending = (data['pending'] as List)
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
      drafts = Map<String, dynamic>.from(data['drafts'] as Map);
      for (final item in pending) {
        if (item['state'] == 'sending') item['state'] = 'uncertain';
      }
      await persist();
    } catch (_) {
      _unreadable = true;
      storageError = '本机发送记录无法读取，请保留应用数据后重试';
    }
    changed();
  }

  Future<void> persist() async {
    if (_unreadable) throw SupportFailure(0, 'STORAGE_FAILED', storageError);
    try {
      await store.save(snapshot());
      storageError = '';
    } catch (_) {
      storageError = '本机发送记录保存失败，请检查存储空间后重试';
      rethrow;
    }
  }

  Future<void> saveDraft() async {
    try {
      await persist();
    } catch (_) {}
    changed();
  }

  bool matches(SupportData item, SupportData message) {
    final body = item['request'] as Map;
    final attachment = message['attachment'] as Map?;
    return message['sender'] == (api.agent ? 'ADMIN' : 'CUSTOMER') &&
        item['conversationId'] == message['conversationId'] &&
        body['clientId'] == message['clientId'] &&
        body['kind'] == message['kind'] &&
        (body['text'] ?? '') == (message['text'] ?? '') &&
        body['attachmentId'] == attachment?['id'];
  }

  Future<void> merge(String id, List<SupportData> messages) async {
    final current = feed(id);
    final byId = {for (final m in current.messages) m['id'] as String: m};
    for (final m in messages) {
      if (m['conversationId'] == id) byId[m['id'] as String] = m;
    }
    current.messages = byId.values.toList()
      ..sort((a, b) => compareIds(a['id'] as String, b['id'] as String));
    final before = pending.length;
    pending.removeWhere((p) => messages.any((m) => matches(p, m)));
    if (before != pending.length) {
      try {
        await persist();
      } catch (_) {}
    }
    changed();
  }

  Future<void> open(String id) async {
    final current = feed(id);
    if (!current.initialized) {
      final page = await api.messages(id);
      final list = supportItems(page);
      await merge(id, list);
      current.since = list.isEmpty ? '0' : list.last['id'] as String;
      current.older = page['cursor'] as String;
      current.hasOlder = page['hasMore'] as bool;
      current.initialized = true;
    } else {
      await sync(id);
    }
    await recover(id);
    changed();
  }

  Future<void> sync(String id) async {
    final current = feed(id);
    if (!current.initialized) return;
    for (var i = 0; i < 5; i++) {
      final page = await api.messages(id, after: current.since);
      await merge(id, supportItems(page));
      final cursor = page['cursor'] as String;
      if (compareIds(cursor, current.since) > 0) current.since = cursor;
      if (page['hasMore'] != true) break;
    }
  }

  Future<void> older(String id) async {
    final current = feed(id);
    if (!current.hasOlder) return;
    final page = await api.messages(id, before: current.older);
    await merge(id, supportItems(page));
    current.older = page['cursor'] as String;
    current.hasOlder = page['hasMore'] as bool;
    changed();
  }

  Future<void> markRead(String id, String visibleId) async {
    final current = feed(id);
    if (current.reading) return;
    final position = compareIds(visibleId, current.since) > 0
        ? current.since
        : visibleId;
    if (position == '0' || compareIds(position, current.readThrough) <= 0)
      return;
    current.reading = true;
    try {
      await api.read(id, position);
      current.readThrough = position;
    } finally {
      current.reading = false;
    }
  }

  Future<void> recover(String id) async {
    for (final item in List<SupportData>.from(pending)) {
      final key = (item['request'] as Map)['clientId'] as String;
      if (item['conversationId'] != id ||
          item['state'] != 'uncertain' ||
          busy.contains(key))
        continue;
      try {
        final message = await api.byClient(id, key);
        if (matches(item, message)) await merge(id, [message]);
      } catch (_) {
        /* 未确认时保留原消息，等待下一次核对或手动重试。 */
      }
    }
  }

  Future<void> sendDraft(String id) async {
    if (storageError.isNotEmpty)
      throw SupportFailure(0, 'STORAGE_FAILED', storageError);
    final current = draft(id);
    final text = (current['text'] as String).trim();
    final attachment = current['attachment'] as Map?;
    if (attachment == null && (text.isEmpty || text.length > 2000))
      throw const SupportFailure(400, 'SUPPORT_INVALID', '请输入消息，不能超过 2000 字');
    final item = <String, dynamic>{
      'conversationId': id,
      'request': {
        'clientId': supportUuid(),
        'kind': attachment?['kind'] ?? 'TEXT',
        'text': attachment == null ? text : null,
        'attachmentId': attachment?['id'],
        'libraryItemId': attachment == null ? null : current['libraryItemId'],
      },
      'attachment': attachment,
      'state': 'sending',
      'error': '',
      'createdAt': DateTime.now().toUtc().toIso8601String(),
    };
    final cleared = attachment == null
        ? <String, dynamic>{
            'text': '',
            'attachment': null,
            'libraryItemId': null,
          }
        : <String, dynamic>{
            ...current,
            'attachment': null,
            'libraryItemId': null,
          };
    final saved = {
      'pending': [...pending, item],
      'drafts': {...drafts, id: cleared},
    };
    // Atomic local commit must finish before sending anything over the network.
    await store.save(saved);
    pending.add(item);
    drafts[id] = cleared;
    changed();
    await attempt(item, false);
  }

  Future<void> attempt(SupportData item, bool retry) async {
    final id = item['conversationId'] as String;
    final body = Map<String, dynamic>.from(item['request'] as Map);
    final key = body['clientId'] as String;
    if (!busy.add(key)) return;
    item['state'] = 'sending';
    item['error'] = '';
    changed();
    try {
      await persist();
      if (retry) {
        try {
          final prior = await api.byClient(id, key);
          if (!matches(item, prior))
            throw const SupportFailure(
              409,
              'IDEMPOTENCY_CONFLICT',
              '消息标识已用于不同内容',
            );
          await merge(id, [prior]);
          return;
        } on SupportFailure catch (e) {
          if (e.status != 404) rethrow;
        }
      }
      final message = await api.send(id, body);
      if (!matches(item, message))
        throw const SupportFailure(0, 'INVALID_RESPONSE', '发送结果未确认，请核对或重试');
      await merge(id, [message]);
    } catch (error) {
      item['state'] =
          error is SupportFailure && error.status >= 400 && error.status < 500
          ? 'failed'
          : 'uncertain';
      item['error'] = supportError(error);
      try {
        await persist();
      } catch (_) {}
      changed();
    } finally {
      busy.remove(key);
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
