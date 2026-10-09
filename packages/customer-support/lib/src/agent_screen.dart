import 'dart:async';
import 'package:flutter/material.dart';
import 'api.dart';
import 'chat.dart';
import 'chat_screen.dart';

class SupportAgentScreen extends StatefulWidget {
  const SupportAgentScreen({
    super.key,
    required this.chat,
    required this.logout,
    required this.notifySound,
  });
  final SupportChat chat;
  final Future<void> Function() logout;
  final VoidCallback notifySound;
  @override
  State<SupportAgentScreen> createState() => _SupportAgentScreenState();
}

class _SupportAgentScreenState extends State<SupportAgentScreen>
    with WidgetsBindingObserver {
  final search = TextEditingController();
  List<SupportData> users = [];
  final seen = <String, String>{};
  int page = 1, total = 0, delay = 2, queryToken = 0;
  bool unread = false,
      sound = true,
      resumed = true,
      running = false,
      first = true;
  String error = '';
  Timer? timer;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    unawaited(poll());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState value) {
    resumed = value == AppLifecycleState.resumed;
    timer?.cancel();
    if (resumed) {
      delay = 2;
      unawaited(poll());
    }
  }

  Future<void> poll() async {
    timer?.cancel();
    if (!mounted || !resumed || running) return;
    running = true;
    final token = queryToken;
    try {
      final result = await widget.chat.api.conversations(
        search: search.text.trim(),
        unread: unread,
        page: page,
      );
      final latest = page == 1 && search.text.isEmpty && !unread
          ? result
          : await widget.chat.api.conversations();
      bool fresh = false;
      for (final user in supportItems(latest)) {
        final id = user['id'] as String;
        final m = user['lastMessage'] as Map?;
        final mid = m?['id'] as String? ?? '0';
        final prior = seen[id];
        if (!first &&
            (user['unreadCount'] as num) > 0 &&
            m?['sender'] == 'CUSTOMER' &&
            (prior == null || compareIds(mid, prior) > 0))
          fresh = true;
        seen[id] = mid;
      }
      first = false;
      if (fresh && mounted) {
        if (sound) widget.notifySound();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('收到新的用户消息'),
            duration: Duration(seconds: 2),
          ),
        );
      }
      if (mounted && token == queryToken)
        setState(() {
          users = supportItems(result);
          total = result['total'] as int;
          error = '';
        });
      delay = 2;
    } catch (cause) {
      if (mounted) setState(() => error = supportError(cause));
      delay = (delay * 2).clamp(2, 30);
    } finally {
      running = false;
      if (mounted && resumed) timer = Timer(Duration(seconds: delay), poll);
    }
  }

  void refresh() {
    queryToken++;
    delay = 2;
    unawaited(poll());
  }

  Future<void> hide(SupportData user) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('移出会话'),
        content: const Text('聊天记录保留，用户再次发送消息后会重新出现。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('移出列表'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.chat.api.hide(user['id'] as String);
      refresh();
    } catch (cause) {
      if (mounted)
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(supportError(cause))));
    }
  }

  @override
  void dispose() {
    queryToken++;
    timer?.cancel();
    search.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: const Color(0xfffaf9f6),
    appBar: AppBar(
      title: const Text('客服工作台'),
      actions: [
        IconButton(
          tooltip: sound ? '关闭声音提醒' : '打开声音提醒',
          onPressed: () => setState(() => sound = !sound),
          icon: Icon(
            sound ? Icons.volume_up_outlined : Icons.volume_off_outlined,
          ),
        ),
        PopupMenuButton<String>(
          onSelected: (value) {
            if (value == 'logout') unawaited(widget.logout());
          },
          itemBuilder: (_) => [
            const PopupMenuItem(value: 'logout', child: Text('退出登录')),
          ],
        ),
      ],
    ),
    body: SafeArea(
      top: false,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: search,
                    decoration: const InputDecoration(
                      hintText: '搜索用户',
                      prefixIcon: Icon(Icons.search),
                    ),
                    onSubmitted: (_) {
                      page = 1;
                      refresh();
                    },
                  ),
                ),
                TextButton(
                  onPressed: () {
                    page = 1;
                    refresh();
                  },
                  child: const Text('搜索'),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Text(
                  '会话 $total',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                const Spacer(),
                const Text('仅看未读'),
                Switch(
                  value: unread,
                  onChanged: (v) {
                    setState(() => unread = v);
                    page = 1;
                    refresh();
                  },
                ),
              ],
            ),
          ),
          if (error.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      '$error，恢复后继续同步',
                      style: const TextStyle(
                        color: Colors.deepOrange,
                        fontSize: 12,
                      ),
                    ),
                  ),
                  TextButton(onPressed: refresh, child: const Text('重连')),
                ],
              ),
            ),
          Expanded(
            child: users.isEmpty
                ? Center(child: Text(error.isEmpty ? '暂无会话' : '暂时无法加载会话'))
                : RefreshIndicator(
                    onRefresh: poll,
                    child: ListView.builder(
                      itemCount: users.length,
                      itemBuilder: (context, index) {
                        final user = users[index];
                        return ListTile(
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16,
                            vertical: 8,
                          ),
                          leading: CircleAvatar(
                            backgroundColor: const Color(0xffffe9be),
                            child: Text(
                              (user['name'] as String).substring(
                                (user['name'] as String).length - 2,
                              ),
                            ),
                          ),
                          title: Text(
                            user['name'] as String,
                            style: const TextStyle(fontWeight: FontWeight.w600),
                          ),
                          subtitle: Text(
                            messagePreview(user['lastMessage'] as SupportData?),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              if ((user['unreadCount'] as num) > 0)
                                Badge(label: Text('${user['unreadCount']}')),
                              IconButton(
                                tooltip: '移出会话',
                                onPressed: () => hide(user),
                                icon: const Icon(Icons.more_horiz),
                              ),
                            ],
                          ),
                          onTap: () async {
                            await Navigator.push(
                              context,
                              MaterialPageRoute<void>(
                                builder: (_) => SupportChatScreen(
                                  chat: widget.chat,
                                  conversationId: user['id'] as String,
                                  title: user['name'] as String,
                                  online: user['online'] as bool,
                                ),
                              ),
                            );
                            refresh();
                          },
                        );
                      },
                    ),
                  ),
          ),
          if (total > 50)
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                TextButton(
                  onPressed: page > 1
                      ? () {
                          page--;
                          refresh();
                        }
                      : null,
                  child: const Text('上一页'),
                ),
                Text('$page'),
                TextButton(
                  onPressed: page * 50 < total
                      ? () {
                          page++;
                          refresh();
                        }
                      : null,
                  child: const Text('下一页'),
                ),
              ],
            ),
        ],
      ),
    ),
  );
}
