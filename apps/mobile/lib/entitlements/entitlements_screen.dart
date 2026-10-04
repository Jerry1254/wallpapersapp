import 'package:flutter/material.dart';
import '../config/app_branding.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import '../catalog/catalog.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../detail/detail_screen.dart';
import '../detail/help_screen.dart';
import '../device/device_session.dart';
import '../downloads/download_manager.dart';
import '../privacy/privacy_gate.dart';
import 'redemption.dart';
import 'ios_acquisition.dart';

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
    this.iosAcquisition,
  });
  final DeviceSessionManager sessions;
  final CatalogRepository catalog;
  final RedemptionCoordinator redemptions;
  final DownloadManager? downloads;
  final AndroidWallpaperPlayback? playback;
  final bool active;
  final VoidCallback? onHome;
  final IosAcquisitionController? iosAcquisition;
  @override
  State<EntitlementsScreen> createState() => _EntitlementsScreenState();
}

class _EntitlementsScreenState extends State<EntitlementsScreen> {
  final List<Wallpaper> items = [];
  bool busy = false, loaded = false, pending = false;
  int page = 0, totalPages = 0;
  String? message;
  String? _acquisitionIds;
  bool _acquisitionReloadRequested = false;
  @override
  void initState() {
    super.initState();
    widget.iosAcquisition?.addListener(_acquisitionChanged);
    if (widget.active) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        load();
        _pending();
      });
    }
  }

  void _acquisitionChanged() {
    final state = widget.iosAcquisition?.state;
    if (!mounted || state == null) return;
    final ids = {
      ...state.freeWallpaperIds,
      ...state.purchasedWallpaperIds,
    }.toList()..sort();
    final version = ids.join(',');
    if (_acquisitionIds == version) return;
    _acquisitionIds = version;
    loaded = false;
    if (busy) {
      _acquisitionReloadRequested = true;
      return;
    }
    if (widget.active) load();
  }

  @override
  void dispose() {
    widget.iosAcquisition?.removeListener(_acquisitionChanged);
    super.dispose();
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
    _acquisitionReloadRequested = false;
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
          .where((item) => item.availableInAndroidPackage)
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
      if (mounted && widget.active && _acquisitionReloadRequested) {
        await load();
      }
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

  Future<void> restorePurchases() async {
    final ios = widget.iosAcquisition;
    if (ios == null || busy) return;
    setState(() => busy = true);
    String result;
    try {
      result = await ios.restore();
    } on IosAcquisitionNotice catch (notice) {
      result = notice.message;
    }
    if (!mounted) return;
    setState(() {
      busy = false;
      message = result;
    });
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(result)));
    // Refresh the installed entitlement list once after the explicit restore.
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
        iosAcquisition: widget.iosAcquisition,
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
        const SizedBox(height: T.space3),
        QjSurface(
          padding: EdgeInsets.zero,
          child: ListTile(
            contentPadding: const EdgeInsets.symmetric(
              horizontal: T.space4,
              vertical: T.space2,
            ),
            leading: const QjIcon('shield-check'),
            title: const Text('用户协议与隐私政策'),
            subtitle: Text(
              '查看${AppBrandingScope.of(context).serviceName}的服务规则与信息处理说明',
            ),
            trailing: const QjIcon('chevron-right'),
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => const PolicyCenterScreen(),
              ),
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
              onPressed: busy
                  ? null
                  : widget.iosAcquisition == null
                  ? load
                  : restorePurchases,
              style: OutlinedButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 12),
              ),
              child: Text(widget.iosAcquisition == null ? '刷新权益' : '恢复购买'),
            ),
          ],
        ),
        const SizedBox(height: T.space2),
        Text(
          widget.iosAcquisition == null
              ? '权益属于当前安装身份。清除 App 数据后将创建新设备。'
              : '免费获取记录保留于本次安装；已购买壁纸可通过原苹果账户恢复。',
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
              description: widget.iosAcquisition == null
                  ? '兑换壁纸后会显示在这里'
                  : '免费获取或购买的壁纸会显示在这里',
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
