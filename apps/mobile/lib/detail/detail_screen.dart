import 'package:flutter/material.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../catalog/catalog.dart';
import '../catalog/catalog_image.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../downloads/download_manager.dart';
import '../downloads/download_panel.dart';
import '../entitlements/redemption.dart';
import '../entitlements/redemption_dialog.dart';
import 'delivery.dart';
import 'detail_preview.dart';
import 'help_screen.dart';
import 'trial_manager.dart';

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
    this.trials,
  });
  final CatalogRepository repository;
  final String id;
  final RedemptionCoordinator? redemptions;
  final DownloadManager? downloads;
  final WallpaperCapabilities capabilities;
  final AndroidWallpaperPlayback? playback;
  final TrialManager? trials;
  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  late Future<Wallpaper> future;
  late WallpaperCapabilities capabilities = widget.capabilities;
  late final TrialManager? trials =
      widget.trials ??
      (widget.downloads == null
          ? null
          : TrialManager(
              widget.downloads!.sessions,
              widget.downloads!.apiBase,
              widget.downloads!.installer,
            ));
  bool? owned;
  String? ownershipError, installedId, _installedKey;
  final scroll = ScrollController();
  bool previewActive = true;
  @override
  void initState() {
    super.initState();
    future = widget.repository.detail(widget.id);
    scroll.addListener(_scrolled);
    _capabilities();
    _ownership();
  }

  void _scrolled() {
    final active = scroll.offset < (MediaQuery.sizeOf(context).width - 40) * 2;
    if (active != previewActive) setState(() => previewActive = active);
  }

  Future<void> _ownership() async {
    if (trials == null) return;
    setState(() {
      owned = null;
      ownershipError = null;
    });
    try {
      final value = await trials!.isOwned(widget.id);
      if (mounted) setState(() => owned = value);
    } catch (_) {
      if (mounted) setState(() => ownershipError = '权益暂时无法确认，请重试');
    }
  }

  Future<void> _capabilities() async {
    try {
      final value = await widget.playback?.capabilities();
      if (mounted && value != null) setState(() => capabilities = value);
    } catch (_) {}
  }

  void _installed(WallpaperEffect effect) {
    final key = '${widget.id}-${effect.name}';
    if (_installedKey == key) return;
    _installedKey = key;
    widget.downloads
        ?.current(widget.id, AndroidWallpaperPlayback.resourceType(effect))
        .then((value) {
          if (mounted && _installedKey == key) {
            setState(() => installedId = value);
          }
        })
        .catchError((_) {
          return null;
        });
  }

  Future<void> _openDownload(WallpaperEffect effect) async {
    final manager = widget.downloads;
    if (manager == null) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
      builder: (sheetContext) => DownloadPanel(
        manager: manager,
        wallpaperId: widget.id,
        resourceType: AndroidWallpaperPlayback.resourceType(effect),
        autoStart: true,
        onReady: (id) {
          Navigator.pop(sheetContext);
          setState(() => installedId = id);
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) _openTarget(effect, id);
          });
        },
      ),
    );
  }

  Future<void> _openTarget(WallpaperEffect effect, String id) async {
    final playback = widget.playback;
    if (playback == null) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
      builder: (_) => WallpaperTargetSheet(
        installedId: id,
        effect: effect,
        playback: playback,
        capabilities: capabilities,
      ),
    );
  }

  Future<void> _action(WallpaperEffect effect) async {
    if (owned == null && trials != null) {
      await _ownership();
      if (owned == null) return;
      if (!mounted) return;
    }
    if (owned == false) {
      final coordinator = widget.redemptions;
      if (coordinator == null) return;
      final granted = await showModalBottomSheet<bool>(
        context: context,
        isScrollControlled: true,
        useSafeArea: true,
        constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
        builder: (_) =>
            RedemptionDialog(coordinator: coordinator, wallpaperId: widget.id),
      );
      if (granted != true) return;
      await _ownership();
      if (owned != true) return;
      await _openDownload(effect);
      return;
    }
    if (installedId != null) {
      await _openTarget(effect, installedId!);
    } else {
      await _openDownload(effect);
    }
  }

  @override
  void dispose() {
    scroll.dispose();
    if (widget.trials == null) trials?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
          child: FutureBuilder<Wallpaper>(
            future: future,
            builder: (context, snapshot) {
              if (snapshot.connectionState != ConnectionState.done) {
                return const Center(child: CircularProgressIndicator());
              }
              if (snapshot.hasError) {
                return ListView(
                  padding: const EdgeInsets.symmetric(horizontal: T.space5),
                  children: [
                    const QjPageHeader(title: '壁纸详情'),
                    const SizedBox(height: T.space6),
                    QjStatePanel(
                      kind: QjStateKind.error,
                      description: snapshot.error is ApiFailure
                          ? (snapshot.error! as ApiFailure).message
                          : '详情加载失败，请重试',
                      onPressed: () {
                        setState(() {
                          future = widget.repository.detail(widget.id);
                        });
                      },
                    ),
                  ],
                );
              }
              final wallpaper = snapshot.requireData;
              final effects = deliveryEffects(
                wallpaper,
                capabilities.platform,
                osVersion: capabilities.osVersion,
              );
              final effect = effects.firstOrNull;
              if (effect != null) {
                WidgetsBinding.instance.addPostFrameCallback((_) {
                  if (mounted) _installed(effect);
                });
              }
              final usable =
                  effect != null &&
                  capabilities.previewEffects.contains(effect) &&
                  WallpaperTarget.values.any(
                    (target) => capabilities.canApply(effect, target),
                  );
              final label = !usable
                  ? '当前设备不支持'
                  : trials != null && owned == null
                  ? '正在确认权益'
                  : owned == true && installedId != null
                  ? '设置壁纸'
                  : '下载壁纸';
              return ListView(
                controller: scroll,
                padding: const EdgeInsets.fromLTRB(
                  T.space5,
                  0,
                  T.space5,
                  T.space6,
                ),
                children: [
                  QjPageHeader(
                    title: wallpaper.title,
                    actionLabel: '观看教程',
                    onAction: () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => const SettingTutorialScreen(),
                      ),
                    ),
                  ),
                  const SizedBox(height: T.space4),
                  Container(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(T.radiusCard),
                      boxShadow: const [T.shadowCard],
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(T.radiusCard),
                      child: AspectRatio(
                        aspectRatio: 1 / 2,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            if (widget.downloads != null &&
                                capabilities.platform ==
                                    ClientPlatform.android &&
                                (effect == WallpaperEffect.video ||
                                    effect == WallpaperEffect.parallax))
                              DetailPreview(
                                key: ValueKey(
                                  '${wallpaper.id}-${effect!.name}',
                                ),
                                manager: widget.downloads!,
                                wallpaperId: wallpaper.id,
                                resourceType:
                                    AndroidWallpaperPlayback.resourceType(
                                      effect,
                                    ),
                                active: previewActive,
                                cover: CatalogImage(
                                  repository: widget.repository,
                                  path: wallpaper.cover,
                                ),
                              )
                            else
                              CatalogImage(
                                repository: widget.repository,
                                path: wallpaper.cover,
                              ),
                            Positioned(
                              left: 0,
                              right: 0,
                              bottom: 0,
                              height: 132,
                              child: IgnorePointer(
                                child: DecoratedBox(
                                  decoration: BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.topCenter,
                                      end: Alignment.bottomCenter,
                                      colors: [
                                        Colors.transparent,
                                        T.colorScrim.withValues(alpha: .38),
                                      ],
                                    ),
                                  ),
                                ),
                              ),
                            ),
                            Positioned(
                              left: T.space4,
                              right: T.space4,
                              bottom: T.space4,
                              child: QjPrimaryAction(
                                label: label,
                                accent: true,
                                loading: trials != null && owned == null,
                                onPressed:
                                    usable &&
                                        (trials == null || owned != null) &&
                                        ownershipError == null
                                    ? () => _action(effect)
                                    : null,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  if (ownershipError != null)
                    Padding(
                      padding: const EdgeInsets.only(top: T.space3),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              ownershipError!,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ),
                          TextButton(
                            onPressed: _ownership,
                            child: const Text('重新加载'),
                          ),
                        ],
                      ),
                    ),
                ],
              );
            },
          ),
        ),
      ),
    ),
  );
}
