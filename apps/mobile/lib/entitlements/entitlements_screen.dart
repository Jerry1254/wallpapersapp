import 'package:flutter/material.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import '../catalog/catalog.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../detail/detail_screen.dart';
import '../detail/help_screen.dart';
import '../device/device_session.dart';
import '../downloads/download_manager.dart';
import 'redemption.dart';

class EntitlementsScreen extends StatefulWidget {
  const EntitlementsScreen({
    super.key,
    required this.sessions,
    required this.catalog,
    required this.redemptions,
    this.downloads,
    this.playback,
    this.active = true,
    this.onHome,
  });
  final DeviceSessionManager sessions;
  final CatalogRepository catalog;
  final RedemptionCoordinator redemptions;
  final DownloadManager? downloads;
  final AndroidWallpaperPlayback? playback;
  final bool active;
  final VoidCallback? onHome;
  @override
  State<EntitlementsScreen> createState() => _EntitlementsScreenState();
}

class _EntitlementsScreenState extends State<EntitlementsScreen> {
  final List<Wallpaper> items = [];
  bool busy = false, loaded = false, pending = false;
  int page = 0, totalPages = 0;
  String? message;
  @override
  void initState() {
    super.initState();
    if (widget.active) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        load();
        _pending();
      });
    }
  }

  @override
  void didUpdateWidget(EntitlementsScreen old) {
    super.didUpdateWidget(old);
    if (widget.active && !old.active && !loaded) {
      load();
      _pending();
    }
  }

  Future<void> _pending() async {
    try {
      final value = await widget.redemptions.store.read();
      if (mounted) setState(() => pending = value != null);
    } catch (_) {}
  }

  Future<void> load({bool more = false}) async {
    if (busy) return;
    setState(() {
      busy = true;
      message = null;
    });
    try {
      final requested = more ? page + 1 : 1;
      final data = await widget.sessions.authenticated(
        '/device/me/entitlements?page=$requested&pageSize=20',
      );
      final incoming = (data['items'] as List)
          .map(
            (e) => Wallpaper.fromJson(e['wallpaper'] as Map<String, dynamic>),
          )
          .toList();
      final pages = (data['page'] as Map<String, dynamic>)['totalPages'] as int;
      if (!mounted) return;
      setState(() {
        if (!more) items.clear();
        for (final item in incoming) {
          if (!items.any((e) => e.id == item.id)) items.add(item);
        }
        page = requested;
        totalPages = pages;
        loaded = true;
      });
    } catch (e) {
      if (mounted) {
        setState(
          () => message = e is DeviceApiError ? e.message : '权益加载失败，请重试',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> confirm() async {
    if (busy) return;
    setState(() => busy = true);
    String result;
    try {
      result = await widget.redemptions.confirm();
    } on RedemptionNotice catch (e) {
      result = e.message;
    } catch (_) {
      result = '无法读取待确认请求，请重试';
    }
    if (!mounted) return;
    setState(() {
      busy = false;
      message = result;
      pending = false;
    });
    await load();
  }

  void detail(Wallpaper item) => Navigator.push(
    context,
    MaterialPageRoute<void>(
      builder: (_) => DetailScreen(
        repository: widget.catalog,
        id: item.id,
        downloads: widget.downloads,
        playback: widget.playback,
        redemptions: widget.redemptions,
      ),
    ),
  );
  @override
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: load,
    child: ListView(
      padding: const EdgeInsets.fromLTRB(T.space5, 0, T.space5, T.space12),
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        QjBrandHeader(
          title: '我的',
          onService: () => Navigator.push(
            context,
            MaterialPageRoute<void>(
              builder: (_) => const HelpScreen(customerService: true),
            ),
          ),
        ),
        const SizedBox(height: T.space4),
        QjTutorialCard(
          onPressed: () => Navigator.push(
            context,
            MaterialPageRoute<void>(
              builder: (_) => HelpScreen(repository: widget.catalog),
            ),
          ),
        ),
        const SizedBox(height: T.space7),
        Row(
          children: [
            Expanded(
              child: Text(
                '已获得壁纸',
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            OutlinedButton(
              onPressed: busy ? null : load,
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 12),
              ),
              child: const Text('刷新权益'),
            ),
          ],
        ),
        const SizedBox(height: T.space2),
        Text(
          '权益属于当前安装身份。清除 App 数据后将创建新设备。',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        if (pending)
          Padding(
            padding: const EdgeInsets.only(top: T.space2),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    '上次兑换尚未确认。',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
                TextButton(
                  onPressed: busy ? null : confirm,
                  child: const Text('确认结果'),
                ),
              ],
            ),
          ),
        if (busy && !loaded)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: T.space8),
            child: Center(child: CircularProgressIndicator()),
          ),
        if (message != null && items.isEmpty)
          Padding(
            padding: const EdgeInsets.only(top: T.space4),
            child: QjStatePanel(
              kind: QjStateKind.error,
              description: message,
              onPressed: load,
            ),
          ),
        if (loaded && items.isEmpty && message == null)
          Padding(
            padding: const EdgeInsets.only(top: T.space4),
            child: QjStatePanel(
              description: '兑换壁纸后会显示在这里',
              onPressed: widget.onHome ?? () {},
            ),
          ),
        if (items.isNotEmpty) ...[
          const SizedBox(height: T.space4),
          for (final item in items)
            Padding(
              padding: const EdgeInsets.only(bottom: T.space3),
              child: QjOwnedRow(
                repository: widget.catalog,
                wallpaper: item,
                onPressed: () => detail(item),
              ),
            ),
        ],
        if (message != null && items.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: T.space3),
            child: Text(message!, style: Theme.of(context).textTheme.bodySmall),
          ),
        if (page < totalPages)
          OutlinedButton(
            onPressed: busy ? null : () => load(more: true),
            child: const Text('加载更多'),
          ),
      ],
    ),
  );
}
