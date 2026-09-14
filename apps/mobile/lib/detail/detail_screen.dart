import 'package:flutter/material.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../catalog/catalog.dart';
import '../catalog/catalog_image.dart';
import 'delivery.dart';
import '../entitlements/redemption.dart';
import '../entitlements/redemption_dialog.dart';
import 'help_screen.dart';
import '../downloads/download_manager.dart';
import '../downloads/download_panel.dart';
import 'package:wallpaper_android/wallpaper_android.dart';

class DetailScreen extends StatefulWidget {
  const DetailScreen({
    super.key,
    required this.repository,
    required this.id,
    this.redemptions,
    this.downloads,
    this.capabilities = const WallpaperCapabilities(
      platform: ClientPlatform.android,
    ),
    this.playback,
  });
  final CatalogRepository repository;
  final String id;
  final RedemptionCoordinator? redemptions;
  final DownloadManager? downloads;
  final WallpaperCapabilities capabilities;
  final AndroidWallpaperPlayback? playback;
  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  late Future<Wallpaper> future;
  WallpaperEffect? selected;
  late WallpaperCapabilities capabilities = widget.capabilities;
  @override
  void initState() {
    super.initState();
    future = widget.repository.detail(widget.id);
    _capabilities();
  }

  Future<void> _capabilities() async {
    try {
      final data = await widget.playback?.capabilities();
      if (mounted && data != null) setState(() => capabilities = data);
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('壁纸详情'),
      actions: [
        IconButton(
          tooltip: '客服',
          onPressed: () => Navigator.push(
            context,
            MaterialPageRoute<void>(
              builder: (_) => const HelpScreen(customerService: true),
            ),
          ),
          icon: const Icon(Icons.support_agent),
        ),
      ],
    ),
    body: FutureBuilder<Wallpaper>(
      future: future,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    snapshot.error is ApiFailure
                        ? (snapshot.error as ApiFailure).message
                        : '详情加载失败，请重试',
                  ),
                  TextButton(
                    onPressed: () => setState(() {
                      future = widget.repository.detail(widget.id);
                    }),
                    child: const Text('重试'),
                  ),
                ],
              ),
            ),
          );
        }
        final wallpaper = snapshot.requireData;
        final effects = deliveryEffects(
          wallpaper,
          capabilities.platform,
          osVersion: capabilities.osVersion,
        );
        final effect = effects.contains(selected)
            ? selected
            : effects.firstOrNull;
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(24),
              child: AspectRatio(
                aspectRatio: 9 / 14,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    CatalogImage(
                      repository: widget.repository,
                      path: wallpaper.cover,
                    ),
                    const Positioned(
                      left: 12,
                      bottom: 12,
                      child: Chip(label: Text('作品封面 · 非原生效果预览')),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 20),
            Text(
              wallpaper.title,
              style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 8),
            Text(wallpaper.kindLabel),
            const SizedBox(height: 16),
            if (effects.isEmpty) const Text('此作品暂无当前平台可交付的资源变体，不可兑换。'),
            Wrap(
              spacing: 8,
              children: effects
                  .map(
                    (item) => ChoiceChip(
                      label: Text(effectLabel(item)),
                      selected: effect == item,
                      onSelected: (_) => setState(() => selected = item),
                    ),
                  )
                  .toList(),
            ),
            const SizedBox(height: 16),
            const Text(
              '系统设置位置',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
            ),
            ...WallpaperTarget.values.map(
              (target) => ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(targetLabel(target)),
                trailing: Text(
                  effect != null && capabilities.canApply(effect, target)
                      ? '可用'
                      : '尚不可用',
                ),
              ),
            ),
            if ((effect == WallpaperEffect.video ||
                    effect == WallpaperEffect.parallax) &&
                capabilities.systemChoosesLiveTarget)
              const Text('动态壁纸设置位置由手机系统选择；桌面和锁屏选项按手机实际提供。两处使用同一倾境服务时共用资源。'),
            if ((effect == WallpaperEffect.video ||
                    effect == WallpaperEffect.parallax) &&
                (capabilities.setupMessage?.isNotEmpty ?? false)) ...[
              Text(capabilities.setupMessage!),
              TextButton(
                onPressed: _capabilities,
                child: const Text('重新检测手机能力'),
              ),
            ],
            if (widget.downloads != null && effect != null) ...[
              const SizedBox(height: 12),
              DownloadPanel(
                key: ValueKey('${wallpaper.id}-${effect.name}'),
                manager: widget.downloads!,
                wallpaperId: wallpaper.id,
                resourceType: switch (effect) {
                  WallpaperEffect.staticImage => 'STATIC_IMAGE',
                  WallpaperEffect.video => 'VIDEO',
                  WallpaperEffect.parallax => 'LAYER_PARALLAX',
                },
                playback: widget.playback,
                capabilities: capabilities,
              ),
            ],
            if (effect == null || !capabilities.previewEffects.contains(effect))
              const Text('此效果的原生预览或系统设置尚不可用；能力确认前不消耗兑换额度。'),
            const SizedBox(height: 16),
            FilledButton(
              onPressed:
                  widget.redemptions != null &&
                      effect != null &&
                      capabilities.previewEffects.contains(effect) &&
                      WallpaperTarget.values.any(
                        (target) => capabilities.canApply(effect, target),
                      )
                  ? () => showDialog<void>(
                      context: context,
                      builder: (_) => RedemptionDialog(
                        coordinator: widget.redemptions!,
                        wallpaperId: wallpaper.id,
                      ),
                    )
                  : null,
              child: const Text('兑换壁纸'),
            ),
            if (wallpaper.copyright?.isNotEmpty ?? false) ...[
              const SizedBox(height: 20),
              Text('版权说明：${wallpaper.copyright}'),
            ],
            TextButton(
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute<void>(builder: (_) => const HelpScreen()),
              ),
              child: const Text('查看壁纸设计与设置教程'),
            ),
          ],
        );
      },
    ),
  );
}
