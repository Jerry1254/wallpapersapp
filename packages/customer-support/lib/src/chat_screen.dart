import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:video_player/video_player.dart';
import 'api.dart';
import 'chat.dart';

class SupportChatScreen extends StatefulWidget {
  const SupportChatScreen({
    super.key,
    required this.chat,
    required this.conversationId,
    required this.title,
    this.online,
    this.backup,
  });
  final SupportChat chat;
  final String conversationId, title;
  final bool? online;
  final VoidCallback? backup;
  @override
  State<SupportChatScreen> createState() => _SupportChatScreenState();
}

class _SupportChatScreenState extends State<SupportChatScreen>
    with WidgetsBindingObserver {
  final input = TextEditingController();
  final scroll = ScrollController();
  Timer? timer;
  bool resumed = true,
      polling = false,
      loading = true,
      sending = false,
      uploading = false,
      olderBusy = false;
  String error = '';
  bool? online;
  int delay = 2;
  DateTime heartbeat = DateTime.fromMillisecondsSinceEpoch(0);
  SupportChat get chat => widget.chat;
  String get cid => widget.conversationId;
  SupportData get draft => chat.draft(cid);
  bool get atBottom =>
      !scroll.hasClients ||
      scroll.position.maxScrollExtent - scroll.offset < 70;
  @override
  void initState() {
    super.initState();
    online = widget.online;
    input.text = draft['text'] as String;
    chat.addListener(changed);
    WidgetsBinding.instance.addObserver(this);
    scroll.addListener(readVisible);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(start());
    });
  }

  Future<void> start() async {
    await poll();
    if (!chat.api.agent && Platform.isAndroid) {
      try {
        final lost = await ImagePicker().retrieveLostData();
        if (lost.files?.isNotEmpty == true && mounted)
          await prepareImage(lost.files!.first);
      } catch (_) {}
    }
  }

  void changed() {
    if (!mounted) return;
    final stick = atBottom;
    if (input.text != draft['text']) input.text = draft['text'] as String;
    setState(() {});
    if (stick)
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && scroll.hasClients) {
          scroll.jumpTo(scroll.position.maxScrollExtent);
          readVisible();
        }
      });
  }

  void readVisible() {
    if (!mounted ||
        !resumed ||
        ModalRoute.of(context)?.isCurrent != true ||
        !atBottom)
      return;
    final messages = chat.feed(cid).messages;
    if (messages.isNotEmpty)
      unawaited(
        chat
            .markRead(cid, messages.last['id'] as String)
            .catchError((Object _) {}),
      );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    resumed = state == AppLifecycleState.resumed;
    timer?.cancel();
    if (resumed) {
      delay = 2;
      unawaited(poll());
    } else if (!chat.api.agent)
      unawaited(chat.api.presence(false).catchError((Object _) {}));
  }

  Future<void> poll() async {
    timer?.cancel();
    if (!mounted || !resumed || polling) return;
    if (ModalRoute.of(context)?.isCurrent == false) {
      if (!chat.api.agent)
        unawaited(chat.api.presence(false).catchError((Object _) {}));
      timer = Timer(const Duration(seconds: 2), poll);
      return;
    }
    polling = true;
    try {
      if (!chat.feed(cid).initialized)
        await chat.open(cid);
      else {
        await chat.sync(cid);
        await chat.recover(cid);
      }
      if (chat.api.agent) {
        final conversation = await chat.api.conversationById(cid);
        online = conversation['online'] as bool;
      }
      if (!chat.api.agent &&
          DateTime.now().difference(heartbeat).inSeconds >= 20) {
        await chat.api.presence(true);
        heartbeat = DateTime.now();
      }
      if (mounted) {
        setState(() {
          error = '';
          loading = false;
        });
        readVisible();
      }
      delay = 2;
    } catch (cause) {
      if (mounted)
        setState(() {
          error = supportError(cause);
          loading = false;
        });
      delay = (delay * 2).clamp(2, 30);
    } finally {
      polling = false;
      if (mounted && resumed) timer = Timer(Duration(seconds: delay), poll);
    }
  }

  void notice(String value) {
    if (mounted)
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(value)));
  }

  Future<void> send() async {
    if (sending || uploading) return;
    setState(() => sending = true);
    try {
      await chat.sendDraft(cid);
      if (mounted)
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (scroll.hasClients) scroll.jumpTo(scroll.position.maxScrollExtent);
        });
    } catch (cause) {
      notice(supportError(cause));
    } finally {
      if (mounted) setState(() => sending = false);
    }
  }

  Future<void> prepareImage(XFile file) async {
    if (await file.length() > 10 * 1024 * 1024) {
      notice('图片不能超过 10 MB');
      return;
    }
    final dir = await getApplicationSupportDirectory();
    final extension = file.name.split('.').last.toLowerCase();
    final suffix = {'jpg', 'jpeg', 'png', 'webp'}.contains(extension)
        ? extension
        : 'bin';
    final path = '${dir.path}/support-upload-${supportUuid()}.$suffix';
    await file.saveTo(path);
    draft['uploadPath'] = path;
    draft['attachment'] = null;
    draft['libraryItemId'] = null;
    await chat.saveDraft();
    if (chat.storageError.isNotEmpty) {
      notice(chat.storageError);
      return;
    }
    await uploadImage();
  }

  Future<void> pickImage() async {
    try {
      final file = await ImagePicker().pickImage(
        source: ImageSource.gallery,
        requestFullMetadata: false,
      );
      if (file != null && mounted) await prepareImage(file);
    } catch (_) {
      notice('无法选择图片，请检查相册权限后重试');
    }
  }

  Future<void> uploadImage() async {
    final path = draft['uploadPath'] as String?;
    if (path == null || uploading) return;
    setState(() => uploading = true);
    try {
      final result = await chat.api.uploadImage(path);
      draft['attachment'] = result;
      draft['uploadPath'] = null;
      await chat.saveDraft();
      try {
        await File(path).delete();
      } catch (_) {}
    } catch (cause) {
      notice('图片上传失败：${supportError(cause)}');
    } finally {
      if (mounted) setState(() => uploading = false);
    }
  }

  Future<void> cancelMedia() async {
    final path = draft['uploadPath'] as String?;
    draft['attachment'] = null;
    draft['libraryItemId'] = null;
    draft['uploadPath'] = null;
    await chat.saveDraft();
    if (path != null) {
      try {
        await File(path).delete();
      } catch (_) {}
    }
  }

  Future<void> library(String kind) async {
    final item = await showModalBottomSheet<SupportData>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => SupportLibraryPicker(api: chat.api, kind: kind),
    );
    if (item == null || !mounted) return;
    if (item['kind'] == 'PHRASE') {
      final selection = input.selection;
      final start = selection.isValid ? selection.start : input.text.length;
      final end = selection.isValid ? selection.end : start;
      final phrase = item['text'] as String;
      final text = input.text.replaceRange(start, end, phrase);
      if (text.length > 2000) {
        notice('插入后超过 2000 字，请先缩短内容');
        return;
      }
      draft['text'] = text;
      input.value = TextEditingValue(
        text: text,
        selection: TextSelection.collapsed(offset: start + phrase.length),
      );
    } else {
      draft['attachment'] = item['attachment'];
      draft['libraryItemId'] = item['id'];
    }
    await chat.saveDraft();
  }

  Future<void> older() async {
    if (olderBusy) return;
    setState(() => olderBusy = true);
    final height = scroll.hasClients ? scroll.position.maxScrollExtent : 0.0;
    try {
      await chat.older(cid);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && scroll.hasClients)
          scroll.jumpTo(scroll.position.maxScrollExtent - height);
      });
    } catch (cause) {
      notice(supportError(cause));
    } finally {
      if (mounted) setState(() => olderBusy = false);
    }
  }

  @override
  void dispose() {
    timer?.cancel();
    chat.removeListener(changed);
    WidgetsBinding.instance.removeObserver(this);
    if (!chat.api.agent)
      unawaited(chat.api.presence(false).catchError((Object _) {}));
    input.dispose();
    scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final feed = chat.feed(cid);
    final pending = chat.pending
        .where((p) => p['conversationId'] == cid)
        .toList();
    return Scaffold(
      backgroundColor: const Color(0xfffaf9f6),
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.title),
            if (online != null)
              Text(
                online! ? '● 在线' : '● 离线',
                style: TextStyle(
                  fontSize: 12,
                  color: online! ? Colors.green : Colors.grey,
                ),
              ),
          ],
        ),
        actions: [
          if (widget.backup != null)
            TextButton(onPressed: widget.backup, child: const Text('微信客服')),
        ],
      ),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            if (error.isNotEmpty || chat.storageError.isNotEmpty)
              Material(
                color: const Color(0xffffefdb),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 6,
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          chat.storageError.isNotEmpty
                              ? chat.storageError
                              : '$error，恢复后继续同步',
                          style: const TextStyle(fontSize: 12),
                        ),
                      ),
                      TextButton(
                        onPressed: () => poll(),
                        child: const Text('重连'),
                      ),
                    ],
                  ),
                ),
              ),
            Expanded(
              child: loading
                  ? const Center(child: CircularProgressIndicator())
                  : ListView(
                      controller: scroll,
                      padding: const EdgeInsets.all(18),
                      children: [
                        if (feed.hasOlder)
                          Center(
                            child: TextButton(
                              onPressed: olderBusy ? null : older,
                              child: Text(olderBusy ? '加载中…' : '加载更早消息'),
                            ),
                          ),
                        if (feed.messages.isEmpty && pending.isEmpty)
                          const Padding(
                            padding: EdgeInsets.all(32),
                            child: Text(
                              '你好，请描述遇到的问题，我们会在这里回复你。',
                              textAlign: TextAlign.center,
                              style: TextStyle(color: Colors.grey),
                            ),
                          ),
                        for (final message in feed.messages)
                          bubble(
                            message,
                            message['sender'] ==
                                (chat.api.agent ? 'ADMIN' : 'CUSTOMER'),
                            null,
                          ),
                        for (final item in pending)
                          bubble(
                            {
                              ...Map<String, dynamic>.from(
                                item['request'] as Map,
                              ),
                              'attachment': item['attachment'],
                              'createdAt': item['createdAt'],
                            },
                            true,
                            item,
                          ),
                      ],
                    ),
            ),
            Material(
              color: Colors.white,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 6, 12, 12),
                child: Column(
                  children: [
                    Row(
                      children: [
                        if (chat.api.agent) ...[
                          TextButton.icon(
                            onPressed: () => library('IMAGE'),
                            icon: const Icon(Icons.image_outlined, size: 20),
                            label: const Text('图片'),
                          ),
                          TextButton.icon(
                            onPressed: () => library('VIDEO'),
                            icon: const Icon(
                              Icons.play_circle_outline,
                              size: 20,
                            ),
                            label: const Text('视频'),
                          ),
                          TextButton(
                            onPressed: () => library('PHRASE'),
                            child: const Text('快捷短语'),
                          ),
                        ] else
                          TextButton.icon(
                            onPressed: uploading || sending ? null : pickImage,
                            icon: const Icon(Icons.image_outlined, size: 20),
                            label: const Text('图片'),
                          ),
                        const Spacer(),
                        const Text(
                          '发送状态可核对',
                          style: TextStyle(fontSize: 10, color: Colors.grey),
                        ),
                      ],
                    ),
                    if (draft['attachment'] != null ||
                        draft['uploadPath'] != null)
                      Container(
                        padding: const EdgeInsets.only(left: 12),
                        decoration: BoxDecoration(
                          color: const Color(0xfffff4de),
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Row(
                          children: [
                            Expanded(
                              child: Text(
                                uploading
                                    ? '图片上传中…'
                                    : draft['uploadPath'] != null
                                    ? '图片上传失败，可重试'
                                    : draft['attachment']['filename'] as String,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                            if (draft['uploadPath'] != null)
                              TextButton(
                                onPressed: uploading ? null : uploadImage,
                                child: const Text('重试上传'),
                              ),
                            IconButton(
                              onPressed: uploading ? null : cancelMedia,
                              tooltip: '取消选择',
                              icon: const Icon(Icons.close, size: 18),
                            ),
                          ],
                        ),
                      ),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Expanded(
                          child: TextField(
                            controller: input,
                            minLines: 1,
                            maxLines: 4,
                            maxLength: 2000,
                            enabled: !sending,
                            decoration: const InputDecoration(
                              hintText: '输入消息…',
                              counterText: '',
                              filled: true,
                              fillColor: Color(0xfff6f5f2),
                              border: OutlineInputBorder(
                                borderSide: BorderSide.none,
                                borderRadius: BorderRadius.all(
                                  Radius.circular(12),
                                ),
                              ),
                            ),
                            onChanged: (value) {
                              draft['text'] = value;
                              unawaited(chat.saveDraft());
                            },
                          ),
                        ),
                        const SizedBox(width: 10),
                        FilledButton(
                          onPressed:
                              sending ||
                                  uploading ||
                                  draft['uploadPath'] != null ||
                                  chat.storageError.isNotEmpty
                              ? null
                              : send,
                          child: Text(sending ? '发送中' : '发送'),
                        ),
                      ],
                    ),
                    if (draft['attachment'] != null &&
                        (draft['text'] as String).isNotEmpty)
                      const Padding(
                        padding: EdgeInsets.only(top: 5),
                        child: Text(
                          '本次发送所选素材，文字会保留',
                          style: TextStyle(fontSize: 11, color: Colors.grey),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget bubble(SupportData message, bool mine, SupportData? item) {
    final attachment = message['attachment'] as Map?;
    final state = item?['state'] as String?;
    return Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.sizeOf(context).width * .82,
        ),
        margin: const EdgeInsets.only(bottom: 18),
        child: Column(
          crossAxisAlignment: mine
              ? CrossAxisAlignment.end
              : CrossAxisAlignment.start,
          children: [
            Text(
              time(message['createdAt'] as String),
              style: const TextStyle(fontSize: 10, color: Colors.grey),
            ),
            const SizedBox(height: 5),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: mine ? const Color(0xffffefca) : Colors.white,
                borderRadius: BorderRadius.circular(13),
                border: Border.all(color: const Color(0xffece7de)),
              ),
              child: attachment == null
                  ? SelectableText(
                      message['text'] as String? ?? '',
                      style: const TextStyle(fontSize: 15, height: 1.5),
                    )
                  : SupportMediaView(
                      api: chat.api,
                      attachment: Map<String, dynamic>.from(attachment),
                    ),
            ),
            if (mine)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      state == null
                          ? '已发送'
                          : state == 'sending'
                          ? '发送中…'
                          : state == 'failed'
                          ? '发送失败'
                          : '发送结果未确认',
                      style: TextStyle(
                        fontSize: 11,
                        color: state == null
                            ? const Color(0xff568366)
                            : state == 'sending'
                            ? Colors.grey
                            : Colors.deepOrange,
                      ),
                    ),
                    if (item != null && state != 'sending')
                      TextButton(
                        onPressed: () => chat.attempt(item, true),
                        child: const Text(
                          '核对并重试',
                          style: TextStyle(fontSize: 11),
                        ),
                      ),
                  ],
                ),
              ),
            if (item != null && (item['error'] as String).isNotEmpty)
              Text(
                item['error'] as String,
                style: const TextStyle(fontSize: 11, color: Colors.deepOrange),
              ),
          ],
        ),
      ),
    );
  }

  String time(String text) {
    final dt = DateTime.tryParse(text)?.toLocal();
    return dt == null
        ? ''
        : '${dt.month}/${dt.day} ${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }
}

class SupportMediaView extends StatefulWidget {
  const SupportMediaView({
    super.key,
    required this.api,
    required this.attachment,
  });
  final SupportApi api;
  final SupportData attachment;
  @override
  State<SupportMediaView> createState() => _SupportMediaViewState();
}

class _SupportMediaViewState extends State<SupportMediaView> {
  late Future<Uri> source = widget.api.media(widget.attachment['id'] as String);
  void retry() => setState(
    () => source = widget.api.media(
      widget.attachment['id'] as String,
      refresh: true,
    ),
  );
  @override
  Widget build(BuildContext context) => FutureBuilder<Uri>(
    future: source,
    builder: (context, snapshot) {
      if (snapshot.hasError)
        return TextButton(onPressed: retry, child: const Text('媒体加载失败，重试'));
      if (!snapshot.hasData)
        return const SizedBox(
          width: 160,
          height: 80,
          child: Center(child: CircularProgressIndicator()),
        );
      if (widget.attachment['kind'] == 'VIDEO')
        return TextButton.icon(
          onPressed: () => Navigator.push(
            context,
            MaterialPageRoute<void>(
              builder: (_) => _SupportVideo(
                api: widget.api,
                id: widget.attachment['id'] as String,
              ),
            ),
          ),
          icon: const Icon(Icons.play_circle_fill, size: 38),
          label: Text(
            '播放视频\n${widget.attachment['filename']}',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
        );
      final url = snapshot.requireData.toString();
      return GestureDetector(
        onTap: () => showDialog<void>(
          context: context,
          builder: (context) => Dialog(
            child: Stack(
              children: [
                InteractiveViewer(
                  child: Image.network(
                    url,
                    errorBuilder: (_, _, _) => TextButton(
                      onPressed: () {
                        Navigator.pop(context);
                        retry();
                      },
                      child: const Text('图片已过期，重新加载'),
                    ),
                  ),
                ),
                Positioned(
                  top: 0,
                  right: 0,
                  child: IconButton(
                    onPressed: () => Navigator.pop(context),
                    icon: const Icon(Icons.close),
                  ),
                ),
              ],
            ),
          ),
        ),
        child: Image.network(
          url,
          width: 220,
          height: 160,
          fit: BoxFit.contain,
          errorBuilder: (_, _, _) =>
              TextButton(onPressed: retry, child: const Text('图片加载失败，重试')),
        ),
      );
    },
  );
}

class _SupportVideo extends StatefulWidget {
  const _SupportVideo({required this.api, required this.id});
  final SupportApi api;
  final String id;
  @override
  State<_SupportVideo> createState() => _SupportVideoState();
}

class _SupportVideoState extends State<_SupportVideo> {
  VideoPlayerController? player;
  String error = '';
  bool loading = true;
  @override
  void initState() {
    super.initState();
    unawaited(load());
  }

  Future<void> load() async {
    await player?.dispose();
    player = null;
    if (mounted)
      setState(() {
        loading = true;
        error = '';
      });
    try {
      final uri = await widget.api.media(widget.id, refresh: true);
      if (!mounted) return;
      final next = VideoPlayerController.networkUrl(uri);
      player = next;
      await next.initialize();
      if (!mounted) return;
      next.addListener(change);
      setState(() => loading = false);
    } catch (_) {
      if (mounted)
        setState(() {
          error = '视频加载失败，请重试';
          loading = false;
        });
    }
  }

  void change() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    player?.removeListener(change);
    unawaited(player?.dispose() ?? Future.value());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: Colors.black,
    appBar: AppBar(title: const Text('客服视频')),
    body: Center(
      child: loading
          ? const CircularProgressIndicator()
          : error.isNotEmpty || player?.value.hasError == true
          ? TextButton(
              onPressed: load,
              child: Text(error.isEmpty ? '播放失败，重试' : error),
            )
          : Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                AspectRatio(
                  aspectRatio: player!.value.aspectRatio,
                  child: VideoPlayer(player!),
                ),
                VideoProgressIndicator(player!, allowScrubbing: true),
                IconButton(
                  color: Colors.white,
                  onPressed: () {
                    player!.value.isPlaying ? player!.pause() : player!.play();
                  },
                  icon: Icon(
                    player!.value.isPlaying
                        ? Icons.pause_circle
                        : Icons.play_circle,
                    size: 48,
                  ),
                ),
              ],
            ),
    ),
  );
}

class SupportLibraryPicker extends StatefulWidget {
  const SupportLibraryPicker({
    super.key,
    required this.api,
    required this.kind,
  });
  final SupportApi api;
  final String kind;
  @override
  State<SupportLibraryPicker> createState() => _SupportLibraryPickerState();
}

class _SupportLibraryPickerState extends State<SupportLibraryPicker> {
  final search = TextEditingController();
  List<SupportData> items = [];
  int page = 1, total = 0, token = 0;
  String error = '';
  bool loading = true;
  @override
  void initState() {
    super.initState();
    unawaited(load());
  }

  Future<void> load() async {
    final mine = ++token;
    setState(() => loading = true);
    try {
      final data = await widget.api.library(
        widget.kind,
        search: search.text.trim(),
        page: page,
      );
      if (mounted && mine == token)
        setState(() {
          items = supportItems(data);
          total = data['total'] as int;
          error = '';
        });
    } catch (cause) {
      if (mounted && mine == token) setState(() => error = supportError(cause));
    } finally {
      if (mounted && mine == token) setState(() => loading = false);
    }
  }

  @override
  void dispose() {
    token++;
    search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SizedBox(
    height: MediaQuery.sizeOf(context).height * .78,
    child: Padding(
      padding: EdgeInsets.fromLTRB(
        16,
        0,
        16,
        MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: Column(
        children: [
          Text(
            widget.kind == 'PHRASE'
                ? '快捷短语'
                : widget.kind == 'VIDEO'
                ? '视频素材'
                : '图片素材',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: search,
                  decoration: const InputDecoration(hintText: '搜索名称或备注'),
                  onSubmitted: (_) {
                    page = 1;
                    unawaited(load());
                  },
                ),
              ),
              TextButton(
                onPressed: () {
                  page = 1;
                  unawaited(load());
                },
                child: const Text('搜索'),
              ),
            ],
          ),
          const SizedBox(height: 8),
          const Text(
            '后台统一管理，选择后需要点击发送',
            style: TextStyle(fontSize: 12, color: Colors.grey),
          ),
          Expanded(
            child: loading
                ? const Center(child: CircularProgressIndicator())
                : error.isNotEmpty
                ? Center(
                    child: TextButton(
                      onPressed: load,
                      child: Text('$error，重试'),
                    ),
                  )
                : items.isEmpty
                ? const Center(child: Text('暂无内容'))
                : ListView(
                    children: [
                      for (final item in items)
                        Card(
                          color: Colors.white,
                          child: Padding(
                            padding: const EdgeInsets.all(12),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  item['title'] as String,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                                if (item['attachment'] != null)
                                  SupportMediaView(
                                    api: widget.api,
                                    attachment: Map<String, dynamic>.from(
                                      item['attachment'] as Map,
                                    ),
                                  )
                                else
                                  Text(item['text'] as String),
                                if ((item['note'] as String? ?? '').isNotEmpty)
                                  Text(
                                    item['note'] as String,
                                    style: const TextStyle(
                                      fontSize: 12,
                                      color: Colors.grey,
                                    ),
                                  ),
                                Align(
                                  alignment: Alignment.centerRight,
                                  child: FilledButton(
                                    onPressed: () =>
                                        Navigator.pop(context, item),
                                    child: Text(
                                      widget.kind == 'PHRASE' ? '插入短语' : '选择',
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                    ],
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
                          unawaited(load());
                        }
                      : null,
                  child: const Text('上一页'),
                ),
                Text('$page'),
                TextButton(
                  onPressed: page * 50 < total
                      ? () {
                          page++;
                          unawaited(load());
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
