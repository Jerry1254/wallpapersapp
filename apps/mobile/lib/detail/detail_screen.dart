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

typedef DetailPreviewBuilder =
    Widget Function(
      DownloadManager manager,
      String wallpaperId,
      String resourceType,
      Widget cover,
      bool active,
    );

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
    this.detailPreviewBuilder,
  });
  final CatalogRepository repository;
  final String id;
  final RedemptionCoordinator? redemptions;
  final DownloadManager? downloads;
  final WallpaperCapabilities capabilities;
  final AndroidWallpaperPlayback? playback;
  final TrialManager? trials;
  final DetailPreviewBuilder? detailPreviewBuilder;
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
  String? ownershipError, _ownershipKey;
  final Map<WallpaperEffect, String?> installedIds = {};
  final Set<WallpaperEffect> _checkingInstalled = {};
  final scroll = ScrollController();
  bool previewActive = true;
  List<WallpaperTutorial>? tutorialCache;
  @override
  void initState() {
    super.initState();
    future = widget.repository.detail(widget.id);
    scroll.addListener(_scrolled);
    _capabilities();
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

  void _loadOwnership(Wallpaper wallpaper) {
    if (wallpaper.isFree || trials == null || _ownershipKey == widget.id) {
      return;
    }
    _ownershipKey = widget.id;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _ownership();
    });
  }

  Future<void> _capabilities() async {
    try {
      final value = await widget.playback?.capabilities();
      if (mounted && value != null) setState(() => capabilities = value);
    } catch (_) {}
  }

  void _installed(WallpaperEffect effect) {
    if (widget.downloads == null) return;
    if (installedIds.containsKey(effect) ||
        _checkingInstalled.contains(effect)) {
      return;
    }
    _checkingInstalled.add(effect);
    widget.downloads
        ?.current(widget.id, AndroidWallpaperPlayback.resourceType(effect))
        .then((value) {
          if (mounted) {
            setState(() => installedIds[effect] = value);
          }
        })
        .catchError((_) {
          return null;
        })
        .whenComplete(() => _checkingInstalled.remove(effect));
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
          setState(() => installedIds[effect] = id);
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
    PlatformResult<void>? initialResult;
    if (capabilities.systemChoosesLiveTarget &&
        effect != WallpaperEffect.staticImage) {
      try {
        initialResult = await playback.apply(id, effect, WallpaperTarget.home);
      } catch (_) {
        initialResult = const PlatformResult(
          OperationStatus.unknown,
          message: '无法打开系统动态壁纸设置，请重试',
        );
      }
      if (!mounted) return;
    }
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
        initialResult: initialResult,
      ),
    );
  }

  Future<void> _action(
    Wallpaper wallpaper,
    List<WallpaperEffect> effects,
  ) async {
    if (!wallpaper.isFree && owned == null && trials != null) {
      await _ownership();
      if (owned == null) return;
      if (!mounted) return;
    }
    if (!wallpaper.isFree && owned == false) {
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
      if (mounted) setState(() => owned = true);
    }
    if (!mounted) return;
    final effect = await showWallpaperEffectPicker(context, effects);
    if (effect == null || !mounted) return;
    final manager = widget.downloads;
    if (manager == null) return;
    String? installed = installedIds[effect];
    if (!installedIds.containsKey(effect)) {
      try {
        installed = await manager.current(
          widget.id,
          AndroidWallpaperPlayback.resourceType(effect),
        );
        if (mounted) setState(() => installedIds[effect] = installed);
      } catch (_) {}
    }
    if (!mounted) return;
    if (installed != null) {
      await _openTarget(effect, installed);
    } else {
      await _openDownload(effect);
    }
  }

  Future<void> _openTutorial(Wallpaper wallpaper) async {
    try {
      final tutorials = tutorialCache ?? await widget.repository.tutorials();
      tutorialCache = tutorials;
      final tutorial = tutorialFor(tutorials, 'ANDROID', wallpaper.kind);
      if (!mounted) return;
      if (tutorial == null) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('对应的设置教程暂未发布')));
        return;
      }
      await showSettingTutorial(context, widget.repository, tutorial);
    } catch (error) {
      if (!mounted) return;
      final message = error is ApiFailure ? error.message : '设置教程暂时无法加载';
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Widget _preview(Wallpaper wallpaper, WallpaperEffect effect, Widget cover) {
    final manager = widget.downloads!;
    final type = AndroidWallpaperPlayback.resourceType(effect);
    return widget.detailPreviewBuilder?.call(
          manager,
          wallpaper.id,
          type,
          cover,
          previewActive,
        ) ??
        DetailPreview(
          key: ValueKey('${wallpaper.id}-${effect.name}'),
          manager: manager,
          wallpaperId: wallpaper.id,
          resourceType: type,
          active: previewActive,
          cover: cover,
        );
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
                          owned = null;
                          ownershipError = null;
                          _ownershipKey = null;
                          future = widget.repository.detail(widget.id);
                        });
                      },
                    ),
                  ],
                );
              }
              final wallpaper = snapshot.requireData;
              _loadOwnership(wallpaper);
              final effects =
                  deliveryEffects(
                        wallpaper,
                        capabilities.platform,
                        osVersion: capabilities.osVersion,
                      )
                      .where(
                        (effect) =>
                            capabilities.previewEffects.contains(effect) &&
                            WallpaperTarget.values.any(
                              (target) => capabilities.canApply(effect, target),
                            ),
                      )
                      .toList();
              final previewEffect = effects.firstOrNull;
              if (effects.isNotEmpty) {
                WidgetsBinding.instance.addPostFrameCallback((_) {
                  if (mounted) {
                    for (final effect in effects) {
                      _installed(effect);
                    }
                  }
                });
              }
              final usable = effects.isNotEmpty;
              final waitsForOwnership =
                  !wallpaper.isFree && trials != null && owned == null;
              final hasAccess = wallpaper.isFree || owned == true;
              final label = !usable
                  ? '当前设备不支持'
                  : waitsForOwnership
                  ? '正在确认权益'
                  : hasAccess &&
                        effects.any((effect) => installedIds[effect] != null)
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
                    actionLabel: '观看设置教程',
                    onAction: () => _openTutorial(wallpaper),
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
                                previewEffect != null)
                              _preview(
                                wallpaper,
                                previewEffect,
                                CatalogImage(
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
                                loading: waitsForOwnership,
                                onPressed:
                                    usable &&
                                        !waitsForOwnership &&
                                        (wallpaper.isFree ||
                                            ownershipError == null)
                                    ? () => _action(wallpaper, effects)
                                    : null,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                  if (!wallpaper.isFree && ownershipError != null)
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
