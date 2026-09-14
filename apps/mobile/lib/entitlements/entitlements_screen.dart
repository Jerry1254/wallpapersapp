import 'package:flutter/material.dart';
import '../catalog/catalog.dart';
import '../catalog/catalog_image.dart';
import '../detail/detail_screen.dart';
import '../detail/help_screen.dart';
import '../device/device_session.dart';
import 'redemption.dart';
import '../downloads/download_manager.dart';

class EntitlementsScreen extends StatefulWidget {
  const EntitlementsScreen({
    super.key,
    required this.sessions,
    required this.catalog,
    required this.redemptions,
    this.downloads,
  });
  final DeviceSessionManager sessions;
  final CatalogRepository catalog;
  final RedemptionCoordinator redemptions;
  final DownloadManager? downloads;
  @override
  State<EntitlementsScreen> createState() => _EntitlementsScreenState();
}

class _EntitlementsScreenState extends State<EntitlementsScreen> {
  final List<Wallpaper> items = [];
  bool busy = false, loaded = false;
  int page = 0, totalPages = 0;
  String? message;
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
      final metadata = data['page'] as Map<String, dynamic>;
      final pages = metadata['totalPages'] as int;
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
    });
  }

  @override
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: load,
    child: ListView(
      padding: const EdgeInsets.all(20),
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        const Text(
          '我的壁纸',
          style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 12),
        const Text('权益属于当前安装身份。清除数据或卸载后无法自动恢复；已获得权益不代表资源已下载。'),
        if (message != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text(message!),
          ),
        FilledButton(
          onPressed: busy ? null : load,
          child: Text(
            busy
                ? '正在处理…'
                : loaded
                ? '刷新我的权益'
                : '加载我的权益',
          ),
        ),
        TextButton(
          onPressed: busy ? null : confirm,
          child: const Text('确认上次兑换结果'),
        ),
        if (widget.downloads != null)
          TextButton(
            onPressed: busy
                ? null
                : () async {
                    try {
                      final bytes = await widget.downloads!.clearUnused();
                      if (mounted) {
                        setState(
                          () => message =
                              '已清理 ${(bytes / 1024 / 1024).toStringAsFixed(1)} MB，正在使用的资源已保留',
                        );
                      }
                    } catch (_) {
                      if (mounted) setState(() => message = '下载期间暂不能清理，请稍后重试');
                    }
                  },
            child: const Text('清理未使用的本地资源'),
          ),
        if (loaded && items.isEmpty)
          const Padding(
            padding: EdgeInsets.all(20),
            child: Text('当前安装尚未获得壁纸权益'),
          ),
        ...items.map(
          (item) => ListTile(
            leading: SizedBox(
              width: 44,
              height: 60,
              child: CatalogImage(repository: widget.catalog, path: item.cover),
            ),
            title: Text(item.title),
            subtitle: const Text('已获得权益 · 查看本地资源与下载'),
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => DetailScreen(
                  repository: widget.catalog,
                  id: item.id,
                  downloads: widget.downloads,
                  redemptions: widget.redemptions,
                ),
              ),
            ),
          ),
        ),
        if (page < totalPages)
          TextButton(
            onPressed: busy ? null : () => load(more: true),
            child: const Text('加载更多权益'),
          ),
        TextButton(
          onPressed: () => Navigator.push(
            context,
            MaterialPageRoute<void>(builder: (_) => const HelpScreen()),
          ),
          child: const Text('壁纸教程'),
        ),
      ],
    ),
  );
}
