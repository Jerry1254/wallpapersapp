import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'api.dart';

typedef SupportRequest =
    Future<SupportData> Function(String path, String method, SupportData? body);

class EndpointSupportApi extends SupportApi {
  EndpointSupportApi({
    required this.agent,
    required this.base,
    required this.request,
    required this.headers,
    required this.scope,
  });
  @override
  final bool agent;
  final Uri base;
  final SupportRequest request;
  final Future<Map<String, String>> Function() headers;
  final Future<String> Function() scope;
  final Map<String, ({Uri url, DateTime expiry})> _media = {};
  String root(String cid) =>
      agent ? '/admin/support/conversations/$cid' : '/device/support';
  @override
  Future<String> cacheScope() => scope();
  @override
  Future<SupportData> conversation() =>
      request('/device/support/conversation', 'GET', null);
  @override
  Future<SupportData> conversationById(String id) =>
      request('/admin/support/conversations/$id', 'GET', null);
  @override
  Future<SupportData> conversations({
    String search = '',
    bool unread = false,
    int page = 1,
  }) => request(
    '/admin/support/conversations?${Uri(queryParameters: {'search': search, 'unread': '$unread', 'page': '$page', 'pageSize': '50'}).query}',
    'GET',
    null,
  );
  @override
  Future<SupportData> messages(
    String id, {
    String? after,
    String? before,
  }) => request(
    '${root(id)}/messages?${Uri(queryParameters: {if (after != null) 'after': after, if (before != null) 'before': before, 'limit': after != null ? '100' : '50'}).query}',
    'GET',
    null,
  );
  @override
  Future<SupportData> send(String id, SupportData body) =>
      request('${root(id)}/messages', 'POST', body);
  @override
  Future<SupportData> byClient(String id, String clientId) =>
      request('${root(id)}/messages/by-client/$clientId', 'GET', null);
  @override
  Future<void> read(String id, String messageId) async {
    await request('${root(id)}/read', 'POST', {'messageId': messageId});
  }

  @override
  Future<void> hide(String id) async {
    await request(root(id), 'DELETE', null);
  }

  @override
  Future<void> presence(bool active) async {
    if (!agent)
      await request('/device/support/presence', 'POST', {'active': active});
  }

  @override
  Future<SupportData> library(
    String kind, {
    String search = '',
    int page = 1,
  }) => request(
    '/admin/support/library?${Uri(queryParameters: {'kind': kind, 'search': search, 'page': '$page', 'pageSize': '50'}).query}',
    'GET',
    null,
  );
  @override
  Future<Uri> media(String id, {bool refresh = false}) async {
    final cached = _media[id];
    if (!refresh &&
        cached != null &&
        cached.expiry.isAfter(DateTime.now().add(const Duration(seconds: 30))))
      return cached.url;
    if (!RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(id))
      throw const SupportFailure(0, 'INVALID_RESPONSE', '媒体编号无效');
    final data = await request(
      '${agent ? '/admin' : '/device'}/support/attachments/$id/access',
      'GET',
      null,
    );
    final path = data['url'] as String;
    if (!RegExp(
      '^/api/v1/support/files/$id\\?ticket=[A-Za-z0-9_-]{43}\$',
    ).hasMatch(path))
      throw const SupportFailure(0, 'INVALID_RESPONSE', '媒体地址无效，请重试');
    final url = base.resolve(path);
    _media[id] = (
      url: url,
      expiry: DateTime.parse(data['expiresAt'] as String),
    );
    return url;
  }

  @override
  Future<SupportData> uploadImage(String path) async {
    final file = File(path);
    final size = await file.length();
    if (size <= 0 || size > 10 * 1024 * 1024)
      throw const SupportFailure(400, 'SUPPORT_INVALID', '图片不能为空，且不能超过 10 MB');
    final boundary = 'qj-${supportUuid()}';
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      return await (() async {
        final route = agent
            ? '/admin/support/attachments'
            : '/device/support/attachments';
        final upload = await client.postUrl(base.resolve('${base.path}$route'));
        upload.followRedirects = false;
        (await headers()).forEach(upload.headers.set);
        upload.headers.set('Accept', 'application/json');
        upload.headers.set(
          'Content-Type',
          'multipart/form-data; boundary=$boundary',
        );
        if (agent)
          upload.add(
            utf8.encode(
              '--$boundary\r\nContent-Disposition: form-data; name="kind"\r\n\r\nIMAGE\r\n',
            ),
          );
        final extension = path.split('.').last.toLowerCase();
        final ext = {'jpg', 'jpeg', 'png', 'webp'}.contains(extension)
            ? extension
            : 'bin';
        final mime = ext == 'png'
            ? 'image/png'
            : ext == 'webp'
            ? 'image/webp'
            : ext == 'jpg' || ext == 'jpeg'
            ? 'image/jpeg'
            : 'application/octet-stream';
        upload.add(
          utf8.encode(
            '--$boundary\r\nContent-Disposition: form-data; name="file"; filename="support.$ext"\r\nContent-Type: $mime\r\n\r\n',
          ),
        );
        await upload.addStream(file.openRead());
        upload.add(utf8.encode('\r\n--$boundary--\r\n'));
        final response = await upload.close();
        final text = await utf8.decoder.bind(response).join();
        return decodeSupportResponse(response.statusCode, text);
      })().timeout(const Duration(seconds: 60));
    } on SupportFailure {
      rethrow;
    } catch (_) {
      throw const SupportFailure(0, 'NETWORK_ERROR', '图片上传未完成，请重试');
    } finally {
      client.close(force: true);
    }
  }
}

SupportData decodeSupportResponse(int status, String text) {
  final data = text.isEmpty
      ? <String, dynamic>{}
      : jsonDecode(text) as SupportData;
  if (status < 200 || status >= 300) {
    final error = data['error'] as Map?;
    final code = error?['code'] as String? ?? 'HTTP_$status';
    final message = switch (code) {
      'SUPPORT_LIBRARY_NOT_FOUND' ||
      'SUPPORT_LIBRARY_CHANGED' => '素材已被修改或删除，请重新选择',
      'IDEMPOTENCY_CONFLICT' => '此消息标识已用于不同内容',
      'RATE_LIMITED' => '操作过于频繁，请稍后重试',
      _ =>
        status == 401 || status == 403
            ? '登录或身份已过期，请重新连接'
            : status == 404
            ? '内容不存在，请刷新后重试'
            : status >= 500
            ? '服务暂时不可用，请核对发送结果后重试'
            : error?['message'] as String? ?? '请求未成功，请检查内容后重试',
    };
    throw SupportFailure(status, code, message);
  }
  return data;
}
