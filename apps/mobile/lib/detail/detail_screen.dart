import 'dart:async';

import 'package:flutter/material.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../catalog/catalog.dart';
import '../catalog/catalog_image.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../device/device_capabilities.dart';
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
      String deliveryPlatform,
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
    this.deviceCapabilities,
    this.detailPreviewBuilder,
  });
  final CatalogRepository repository;
  final String id;
  final RedemptionCoordinator? redemptions;
  final DownloadManager? downloads;
  final WallpaperCapabilities capabilities;
  final AndroidWallpaperPlayback? playback;
  final TrialManager? trials;
  final DeviceCapabilityManager? deviceCapabilities;
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

  Future<void> _openDownload(WallpaperDeliveryOption option) async {
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
        deliveryPlatform: option.deliveryPlatform,
        resourceType: option.resourceType,
        autoStart: true,
        onReady: (id) {
          Navigator.pop(sheetContext);
          setState(() => installedIds[option.effect] = id);
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) _openTarget(option, id);
          });
        },
      ),
    );
  }

  Future<void> _openTarget(WallpaperDeliveryOption option, String id) async {
    final playback = widget.playback;
    if (playback == null) return;
    final effect = option.effect;
    final targetCapabilities = capabilitiesForOption(capabilities, option);
    PlatformResult<void>? initialResult;
    if (targetCapabilities.systemChoosesLiveTarget &&
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
      unawaited(_recordResult(option, WallpaperTarget.home, initialResult));
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
        capabilities: targetCapabilities,
        initialResult: initialResult,
        onResult: (target, result) => _recordResult(option, target, result),
      ),
    );
  }

  Future<void> _recordResult(
    WallpaperDeliveryOption option,
    WallpaperTarget target,
    PlatformResult<void> result,
  ) async {
    final manager = widget.deviceCapabilities;
    if (manager == null) return;
    try {
      if (result.status == OperationStatus.completed) {
        await manager.recordSuccessfulSet(
          option.deliveryPlatform,
          option.resourceType,
        );
      } else if (result.status == OperationStatus.unsupported) {
        await manager.recordUnsupported(
          option.deliveryPlatform,
          option.resourceType,
        );
        if (mounted) {
          setState(() => future = widget.repository.detail(widget.id));
        }
      }
    } catch (_) {}
  }

  Future<void> _action(
    Wallpaper wallpaper,
    List<WallpaperDeliveryOption> options,
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
    final effect = await showWallpaperEffectPicker(
      context,
      options.map((item) => item.effect).toList(growable: false),
    );
    if (effect == null || !mounted) return;
    final option = options.firstWhere((item) => item.effect == effect);
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
      await _openTarget(option, installed);
    } else {
      await _openDownload(option);
    }
  }

  Future<void> _openTutorial(Wallpaper wallpaper) async {
    try {
      final tutorials = tutorialCache ?? await widget.repository.tutorials();
      tutorialCache = tutorials;
      final options = deliveryOptions(wallpaper, capabilities);
      final capability =
          options.firstOrNull?.capability ??
          wallpaper.availableCapabilities.firstOrNull;
      final tutorial = capability == null
          ? null
          : tutorialForCapability(tutorials, capability);
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

  Widget _preview(
    Wallpaper wallpaper,
    WallpaperDeliveryOption option,
    Widget cover,
  ) {
    final manager = widget.downloads!;
    final type = option.resourceType;
    return widget.detailPreviewBuilder?.call(
          manager,
          wallpaper.id,
          option.deliveryPlatform,
          type,
          cover,
          previewActive,
        ) ??
        DetailPreview(
          key: ValueKey('${wallpaper.id}-${option.effect.name}'),
          manager: manager,
          wallpaperId: wallpaper.id,
          deliveryPlatform: option.deliveryPlatform,
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
                final failure = snapshot.error is ApiFailure
                    ? snapshot.error! as ApiFailure
                    : null;
                final unavailable =
                    failure?.code == 'WALLPAPER_NOT_AVAILABLE_FOR_DEVICE';
                return ListView(
                  padding: const EdgeInsets.symmetric(horizontal: T.space5),
                  children: [
                    const QjPageHeader(title: '壁纸详情'),
                    const SizedBox(height: T.space6),
                    QjStatePanel(
                      kind: QjStateKind.error,
                      description: failure != null
                          ? failure.message
                          : '详情加载失败，请重试',
                      onPressed: () {
                        if (unavailable) {
                          Navigator.maybePop(context);
                          return;
                        }
                        setState(() {
                          owned = null;
                          ownershipError = null;
                          _ownershipKey = null;
                          future = widget.repository.detail(widget.id);
                        });
                      },
                      actionLabel: unavailable ? '返回列表' : '重新加载',
                    ),
                  ],
                );
              }
              final wallpaper = snapshot.requireData;
              _loadOwnership(wallpaper);
              final options = deliveryOptions(wallpaper, capabilities);
              final previewOption = options.firstOrNull;
              if (options.isNotEmpty) {
                WidgetsBinding.instance.addPostFrameCallback((_) {
                  if (mounted) {
                    for (final option in options) {
                      _installed(option.effect);
                    }
                  }
                });
              }
              final usable = options.isNotEmpty;
              final waitsForOwnership =
                  !wallpaper.isFree && trials != null && owned == null;
              final hasAccess = wallpaper.isFree || owned == true;
              final label = !usable
                  ? '当前设备不支持'
                  : waitsForOwnership
                  ? '正在确认权益'
                  : hasAccess &&
                        options.any(
                          (option) => installedIds[option.effect] != null,
                        )
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
                                previewOption != null)
                              _preview(
                                wallpaper,
                                previewOption,
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
                                    ? () => _action(wallpaper, options)
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
