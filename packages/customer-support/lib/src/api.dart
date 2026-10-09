import 'dart:math';

typedef SupportData = Map<String, dynamic>;

class SupportFailure implements Exception {
  const SupportFailure(this.status, this.code, this.message);
  final int status;
  final String code, message;
  @override
  String toString() => message;
}

String supportError(Object error) =>
    error is SupportFailure ? error.message : '网络或本机存储暂时不可用，请重试';
String supportUuid() {
  final random = Random.secure();
  final bytes = List.generate(16, (_) => random.nextInt(256));
  bytes[6] = bytes[6] & 15 | 64;
  bytes[8] = bytes[8] & 63 | 128;
  final s = bytes.map((v) => v.toRadixString(16).padLeft(2, '0')).join();
  return '${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}';
}

int compareIds(String a, String b) =>
    BigInt.parse(a).compareTo(BigInt.parse(b));
List<SupportData> supportItems(SupportData page) => (page['items'] as List)
    .map((e) => Map<String, dynamic>.from(e as Map))
    .toList();
String messagePreview(SupportData? message) => switch (message?['kind']) {
  'IMAGE' => '[图片]',
  'VIDEO' => '[视频]',
  _ => message?['text'] as String? ?? '暂无消息',
};

abstract class SupportApi {
  bool get agent;
  Future<String> cacheScope();
  Future<SupportData> conversation();
  Future<SupportData> conversations({
    String search = '',
    bool unread = false,
    int page = 1,
  });
  Future<SupportData> conversationById(String id);
  Future<SupportData> messages(String id, {String? after, String? before});
  Future<SupportData> send(String id, SupportData body);
  Future<SupportData> byClient(String id, String clientId);
  Future<void> read(String id, String messageId);
  Future<void> hide(String id);
  Future<void> presence(bool active);
  Future<SupportData> library(String kind, {String search = '', int page = 1});
  Future<SupportData> uploadImage(String path);
  Future<Uri> media(String attachmentId, {bool refresh = false});
}
