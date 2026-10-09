import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:qingjing_customer_support/customer_support.dart';
import '../device/device_session.dart';
import 'customer_service.dart';
import 'inbox.dart';
export 'inbox.dart';

EndpointSupportApi customerSupportApi(
  Uri base,
  DeviceSessionManager sessions,
  Future<Map<String, String>> Function() versionHeaders,
) => EndpointSupportApi(
  agent: false,
  base: base,
  scope: () async =>
      '${base.toString()}:customer:${(await sessions.session()).credentialKeyId}',
  headers: () async => {
    ...await versionHeaders(),
    'Authorization': 'Bearer ${(await sessions.session()).token}',
  },
  request: (path, method, body) async {
    try {
      return await sessions.authenticated(
        path,
        method: method,
        body: body == null ? null : jsonEncode(body),
        signed: method != 'GET',
      );
    } on DeviceApiError catch (error) {
      throw SupportFailure(error.status, error.code, switch (error.code) {
        'SUPPORT_LIBRARY_NOT_FOUND' => '素材已不可用，请重新选择',
        'SUPPORT_ATTACHMENT_FORBIDDEN' => '此图片不属于当前会话',
        'SUPPORT_INVALID' => '消息内容不符合要求，请检查后重试',
        'SUPPORT_MESSAGE_NOT_FOUND' => '尚未找到已保存的消息',
        'RATE_LIMITED' => '操作过于频繁，请稍后重试',
        _ => error.message,
      });
    }
  },
);

class CustomerSupportPage extends StatefulWidget {
  const CustomerSupportPage({super.key});
  @override
  State<CustomerSupportPage> createState() => _CustomerSupportPageState();
}

class _CustomerSupportPageState extends State<CustomerSupportPage> {
  SupportChat? chat;
  String? cid;
  String error = '';
  bool started = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!started) {
      started = true;
      load();
    }
  }

  Future<void> load() async {
    setState(() => error = '');
    SupportChat? next;
    try {
      final api = CustomerSupportScope.of(context);
      if (api == null) {
        throw const SupportFailure(0, 'NOT_CONFIGURED', '客服接口尚未配置');
      }
      final store = await FileSupportStore.scoped(await api.cacheScope());
      next = SupportChat(api, store);
      await next.initialize();
      final conversation = await api.conversation();
      if (!mounted) {
        next.dispose();
        return;
      }
      chat?.dispose();
      setState(() {
        chat = next;
        cid = conversation['id'] as String;
      });
      next = null;
    } catch (cause) {
      next?.dispose();
      if (mounted) setState(() => error = supportError(cause));
    }
  }

  @override
  void dispose() {
    chat?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => chat != null && cid != null
      ? SupportChatScreen(
          chat: chat!,
          conversationId: cid!,
          title: '在线客服',
          backup: () => showWechatServiceDialog(context),
        )
      : Scaffold(
          appBar: AppBar(
            title: const Text('在线客服'),
            actions: [
              TextButton(
                onPressed: () => showWechatServiceDialog(context),
                child: const Text('微信客服'),
              ),
            ],
          ),
          body: Center(
            child: error.isEmpty
                ? const CircularProgressIndicator()
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Padding(
                        padding: const EdgeInsets.all(24),
                        child: Text(error, textAlign: TextAlign.center),
                      ),
                      FilledButton(onPressed: load, child: const Text('重新连接')),
                    ],
                  ),
          ),
        );
}
