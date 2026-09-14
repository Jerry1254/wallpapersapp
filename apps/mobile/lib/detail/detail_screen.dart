import 'package:flutter/material.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../catalog/catalog.dart';
import '../catalog/catalog_image.dart';
import 'delivery.dart';
import 'help_screen.dart';

class DetailScreen extends StatefulWidget {
  const DetailScreen({
    super.key,
    required this.repository,
    required this.id,
    this.capabilities = const WallpaperCapabilities(
      platform: ClientPlatform.android,
    ),
  });
  final CatalogRepository repository;
  final String id;
  final WallpaperCapabilities capabilities;
  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  late Future<Wallpaper> future;
  WallpaperEffect? selected;
  @override
  void initState() {
    super.initState();
    future = widget.repository.detail(widget.id);
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
          widget.capabilities.platform,
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
                  effect != null && widget.capabilities.canApply(effect, target)
                      ? '可用'
                      : '尚不可用',
                ),
              ),
            ),
            const Text('当前原生预览、下载和系统设置尚未接入。资源类型匹配不代表手机支持设置；能力确认前不消耗兑换额度。'),
            const SizedBox(height: 16),
            const FilledButton(onPressed: null, child: Text('试用与兑换待能力接入')),
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
