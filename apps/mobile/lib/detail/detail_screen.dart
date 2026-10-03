import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:io';
import 'package:wallpaper_android/wallpaper_android.dart';
import '../catalog/catalog.dart';
import '../catalog/catalog_image.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../downloads/download_manager.dart';
import '../downloads/download_panel.dart';
import '../entitlements/redemption.dart';
import '../entitlements/redemption_dialog.dart';
import '../entitlements/ios_acquisition.dart';
import 'delivery.dart';
import 'detail_preview.dart';
import 'help_screen.dart';
import 'ios_live_photo_preview.dart';
import 'ios_exchange_sheet.dart';
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
    this.playback,
    this.trials,
    this.detailPreviewBuilder,
    this.iosAcquisition,
  });
  final CatalogRepository repository;
  final String id;
  final RedemptionCoordinator? redemptions;
  final DownloadManager? downloads;
  final AndroidWallpaperPlayback? playback;
  final TrialManager? trials;
  final DetailPreviewBuilder? detailPreviewBuilder;
  final IosAcquisitionController? iosAcquisition;
  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen>
    with WidgetsBindingObserver {
  late Future<Wallpaper> future;
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
  final Map<String, String?> installedIds = {};
  final Set<String> _checkingInstalled = {};
  String? selectedPreviewKey;
  List<WallpaperTutorial>? tutorialCache;
  bool _fullScreen = false;
  bool get _isIosDetail => Platform.isIOS || widget.iosAcquisition != null;
  bool get _allowsFullScreen => !_isIosDetail;
  final _previewKey = GlobalKey();
  @override
  void initState() {
    super.initState();
    future = widget.repository.detail(widget.id);
    widget.downloads?.addListener(_downloadChanged);
    widget.iosAcquisition?.addListener(_downloadChanged);
    if (widget.iosAcquisition != null) {
      WidgetsBinding.instance.addObserver(this);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(widget.iosAcquisition!.refreshPrices());
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && widget.iosAcquisition != null) {
      unawaited(widget.iosAcquisition!.refreshPrices());
    }
  }

  void _downloadChanged() {
    if (mounted) setState(() {});
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
    if (widget.iosAcquisition != null) return;
    if (wallpaper.isFree || trials == null || _ownershipKey == widget.id) {
      return;
    }
    _ownershipKey = widget.id;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _ownership();
    });
  }

  void _installed(WallpaperDeliveryOption option) {
    if (widget.downloads == null) return;
    if (!option.availableInClient ||
        installedIds.containsKey(option.key) ||
        _checkingInstalled.contains(option.key)) {
      return;
    }
    _checkingInstalled.add(option.key);
    widget.downloads
        ?.current(widget.id, option.resourceType)
        .then((value) {
          if (mounted) {
            setState(() => installedIds[option.key] = value);
          }
        })
        .catchError((_) {
          return null;
        })
        .whenComplete(() => _checkingInstalled.remove(option.key));
  }

  Future<void> _openDownload(
    WallpaperDeliveryOption option, {
    bool afterRedemption = false,
  }) async {
    final manager = widget.downloads;
    if (manager == null) return;
    await manager.download(
      widget.id,
      option.deliveryPlatform,
      option.resourceType,
      afterRedemption: afterRedemption,
    );
    final state = manager.value;
    if (mounted &&
        state.status == 'completed' &&
        state.wallpaperId == widget.id &&
        state.deliveryPlatform == option.deliveryPlatform &&
        state.resourceType == option.resourceType) {
      setState(() => installedIds[option.key] = state.installedId);
    }
  }

  Future<void> _openTarget(WallpaperDeliveryOption option, String id) async {
    final playback = widget.playback;
    if (playback == null) return;
    final effect = option.effect;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
      builder: (_) => WallpaperTargetSheet(
        installedId: id,
        effect: effect,
        playback: playback,
        placements: option.placements,
      ),
    );
  }

  Future<void> _action(
    Wallpaper wallpaper,
    WallpaperDeliveryOption option,
  ) async {
    final ios = widget.iosAcquisition;
    if (ios != null && !wallpaper.isFree) {
      try {
        if (!await confirmIosExchange(
              context,
              acquisition: ios,
              wallpaperId: widget.id,
              title: wallpaper.title,
            ) ||
            !mounted) {
          return;
        }
        if (!await ios.acquire(widget.id) || !mounted) return;
        await _openDownload(option);
      } on IosAcquisitionNotice catch (notice) {
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(notice.message)));
        }
      }
      return;
    }
    var redeemedNow = false;
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
      if (mounted) {
        setState(() => owned = true);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('兑换成功'), duration: Duration(seconds: 1)),
        );
      }
      redeemedNow = true;
    }
    if (!mounted) return;
    if (!option.availableInClient) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('您的手机不支持此壁纸，请尝试切换其他类型')));
      return;
    }
    final manager = widget.downloads;
    if (manager == null) return;
    String? installed = installedIds[option.key];
    if (!installedIds.containsKey(option.key)) {
      try {
        installed = await manager.current(widget.id, option.resourceType);
        if (mounted) setState(() => installedIds[option.key] = installed);
      } catch (_) {}
    }
    if (!mounted) return;
    if (installed != null && !Platform.isIOS) {
      await _openTarget(option, installed);
    } else {
      await _openDownload(option, afterRedemption: redeemedNow);
    }
  }

  Future<void> _openTutorial(Wallpaper wallpaper) async {
    try {
      final tutorials = tutorialCache ?? await widget.repository.tutorials();
      tutorialCache = tutorials;
      final options = deliveryOptions(wallpaper);
      final capability =
          options
              .where((option) => option.key == selectedPreviewKey)
              .firstOrNull
              ?.capability ??
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
          true,
        ) ??
        DetailPreview(
          key: ValueKey('${wallpaper.id}-${option.key}'),
          manager: manager,
          wallpaperId: wallpaper.id,
          deliveryPlatform: option.deliveryPlatform,
          resourceType: type,
          active: true,
          fill: true,
          cover: cover,
        );
  }

  Widget _iosPreview(
    Wallpaper wallpaper,
    WallpaperDeliveryOption option,
    Widget cover,
  ) => IosLivePhotoPreviewView(
    key: ValueKey('ios-${wallpaper.id}-${option.key}'),
    manager: widget.downloads!,
    wallpaperId: wallpaper.id,
    deliveryPlatform: option.deliveryPlatform,
    resourceType: option.resourceType,
    cover: cover,
    fit: _fullScreen ? BoxFit.contain : BoxFit.cover,
  );

  Widget _header(Wallpaper wallpaper) {
    final back = IconButton(
      tooltip: '返回',
      onPressed: () => Navigator.maybePop(context),
      icon: const QjIcon('chevron-left', size: 22),
    );
    final title = Text(
      '壁纸详情',
      textAlign: TextAlign.center,
      style: Theme.of(
        context,
      ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
    );
    final tutorial = TextButton(
      style: TextButton.styleFrom(
        foregroundColor: T.colorAccentStrong,
        padding: const EdgeInsets.symmetric(horizontal: 8),
      ),
      onPressed: () => _openTutorial(wallpaper),
      child: const Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          QjIcon('circle-play', size: 17, color: T.colorAccentStrong),
          SizedBox(width: 5),
          Text('设置教程'),
        ],
      ),
    );
    if (_isIosDetail) {
      return Stack(
        fit: StackFit.expand,
        children: [
          Center(child: title),
          Align(alignment: Alignment.centerLeft, child: back),
          Align(alignment: Alignment.centerRight, child: tutorial),
        ],
      );
    }
    return Row(
      children: [
        back,
        Expanded(child: title),
        tutorial,
      ],
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.downloads?.removeListener(_downloadChanged);
    widget.iosAcquisition?.removeListener(_downloadChanged);
    if (widget.trials == null) trials?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !_fullScreen,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop && _fullScreen) setState(() => _fullScreen = false);
    },
    child: Scaffold(
      backgroundColor: _fullScreen ? Colors.black : null,
      body: AnnotatedRegion<SystemUiOverlayStyle>(
        value: _fullScreen
            ? const SystemUiOverlayStyle(
                statusBarColor: Colors.transparent,
                statusBarBrightness: Brightness.dark,
                statusBarIconBrightness: Brightness.light,
                systemNavigationBarColor: Colors.black,
                systemNavigationBarIconBrightness: Brightness.light,
              )
            : qjSystemUiOverlayStyle,
        child: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: BoxConstraints(
                maxWidth: _fullScreen ? double.infinity : T.sizeContentMax,
              ),
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
                  final options = deliveryOptions(wallpaper);
                  final previewOption =
                      options
                          .where((option) => option.key == selectedPreviewKey)
                          .firstOrNull ??
                      options.firstOrNull;
                  final previewTabs = options;
                  final showsPreviewTabs = previewTabs.length > 1;
                  if (options.isNotEmpty) {
                    WidgetsBinding.instance.addPostFrameCallback((_) {
                      if (mounted) {
                        for (final option in options) {
                          _installed(option);
                        }
                      }
                    });
                  }
                  final usable = options.isNotEmpty;
                  final ios = widget.iosAcquisition;
                  final usesIosAcquisition = ios != null && !wallpaper.isFree;
                  final waitsForOwnership = usesIosAcquisition
                      ? ios.busy
                      : !wallpaper.isFree && trials != null && owned == null;
                  final hasAccess =
                      wallpaper.isFree ||
                      (usesIosAcquisition
                          ? ios.owns(widget.id)
                          : owned == true);
                  final downloadState = widget.downloads?.value;
                  final downloadingCurrent =
                      previewOption != null &&
                      downloadState?.busy == true &&
                      downloadState?.wallpaperId == widget.id &&
                      downloadState?.deliveryPlatform ==
                          previewOption.deliveryPlatform &&
                      downloadState?.resourceType == previewOption.resourceType;
                  final progress =
                      downloadingCurrent && downloadState!.total > 0
                      ? (downloadState.received / downloadState.total * 100)
                            .clamp(0, 100)
                            .round()
                      : null;
                  final justSavedToPhotos =
                      Platform.isIOS &&
                      previewOption != null &&
                      downloadState?.status == 'completed' &&
                      downloadState?.wallpaperId == widget.id &&
                      downloadState?.deliveryPlatform ==
                          previewOption.deliveryPlatform &&
                      downloadState?.resourceType == previewOption.resourceType;
                  final label = downloadingCurrent
                      ? progress == null
                            ? '下载中'
                            : '下载中 $progress%'
                      : !usable
                      ? '暂无可用资源'
                      : usesIosAcquisition
                      ? justSavedToPhotos && ios.owns(widget.id)
                            ? '已保存到相册，请设置'
                            : ios.label(widget.id)
                      : waitsForOwnership
                      ? '正在确认权益'
                      : !wallpaper.isFree && owned == false
                      ? '兑换并下载'
                      : justSavedToPhotos
                      ? '已保存到相册，请设置'
                      : Platform.isIOS &&
                            previewOption != null &&
                            installedIds[previewOption.key] != null
                      ? '再次下载'
                      : hasAccess &&
                            previewOption != null &&
                            (!previewOption.availableInClient ||
                                installedIds[previewOption.key] != null)
                      ? '设置壁纸'
                      : '下载壁纸';
                  final canAct =
                      usable &&
                      !waitsForOwnership &&
                      !downloadingCurrent &&
                      (usesIosAcquisition
                          ? ios.canAcquire(widget.id)
                          : wallpaper.isFree || ownershipError == null);
                  final cover = CatalogImage(
                    repository: widget.repository,
                    path: wallpaper.cover,
                    fit: _fullScreen ? BoxFit.contain : BoxFit.cover,
                  );
                  return Padding(
                    padding: _fullScreen
                        ? EdgeInsets.zero
                        : const EdgeInsets.fromLTRB(
                            T.space5,
                            0,
                            T.space5,
                            T.space4,
                          ),
                    child: Column(
                      children: [
                        if (!_fullScreen) ...[
                          SizedBox(
                            height: T.sizeTopBar,
                            key: const ValueKey('detail-header'),
                            child: _header(wallpaper),
                          ),
                          if (showsPreviewTabs) ...[
                            SizedBox(height: _isIosDetail ? 2 : T.space2),
                            SizedBox(
                              height: 40,
                              child: LayoutBuilder(
                                builder: (context, available) =>
                                    SingleChildScrollView(
                                      scrollDirection: Axis.horizontal,
                                      child: ConstrainedBox(
                                        constraints: BoxConstraints(
                                          minWidth: available.maxWidth,
                                        ),
                                        child: Row(
                                          mainAxisAlignment:
                                              MainAxisAlignment.center,
                                          children: previewTabs
                                              .map(
                                                (option) => Padding(
                                                  padding: EdgeInsets.only(
                                                    right:
                                                        option ==
                                                            previewTabs.last
                                                        ? 0
                                                        : T.space2,
                                                  ),
                                                  child: QjFilterChip(
                                                    label: option.label,
                                                    selected:
                                                        option.key ==
                                                        previewOption?.key,
                                                    onPressed: () => setState(
                                                      () => selectedPreviewKey =
                                                          option.key,
                                                    ),
                                                  ),
                                                ),
                                              )
                                              .toList(growable: false),
                                        ),
                                      ),
                                    ),
                              ),
                            ),
                          ],
                          const SizedBox(height: T.space3),
                        ],
                        Expanded(
                          child: Container(
                            key: _previewKey,
                            decoration: BoxDecoration(
                              color: Colors.black,
                              borderRadius: BorderRadius.circular(
                                _fullScreen ? 0 : T.radiusCard,
                              ),
                            ),
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(
                                _fullScreen ? 0 : T.radiusCard,
                              ),
                              child: Stack(
                                key: const ValueKey('detail-preview'),
                                fit: StackFit.expand,
                                children: [
                                  if (widget.downloads != null &&
                                      previewOption != null &&
                                      previewOption.availableInClient &&
                                      Platform.isIOS &&
                                      previewOption.deliveryPlatform == 'IOS' &&
                                      previewOption.resourceType ==
                                          'LIVE_PHOTO')
                                    _iosPreview(wallpaper, previewOption, cover)
                                  else if (widget.downloads != null &&
                                      previewOption != null &&
                                      previewOption.availableInClient &&
                                      !Platform.isIOS)
                                    _preview(wallpaper, previewOption, cover)
                                  else
                                    cover,
                                  if (previewOption != null)
                                    Positioned(
                                      top: 14,
                                      left: 14,
                                      child: IgnorePointer(
                                        child: DecoratedBox(
                                          decoration: BoxDecoration(
                                            color: const Color(0xB3191817),
                                            borderRadius: BorderRadius.circular(
                                              20,
                                            ),
                                            border: Border.all(
                                              color: Colors.white38,
                                            ),
                                          ),
                                          child: Padding(
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 12,
                                              vertical: 8,
                                            ),
                                            child: Row(
                                              mainAxisSize: MainAxisSize.min,
                                              children: [
                                                Icon(
                                                  previewOption.resourceType ==
                                                          'LAYER_PARALLAX'
                                                      ? Icons.layers_outlined
                                                      : previewOption
                                                                .resourceType ==
                                                            'STATIC_IMAGE'
                                                      ? Icons.image_outlined
                                                      : Icons
                                                            .play_circle_outline_rounded,
                                                  color: Colors.white,
                                                  size: 18,
                                                ),
                                                const SizedBox(width: 6),
                                                Text(
                                                  previewOption.resourceType ==
                                                          'LAYER_PARALLAX'
                                                      ? '4D 景深'
                                                      : previewOption.label,
                                                  style: const TextStyle(
                                                    color: Colors.white,
                                                    fontWeight: FontWeight.w600,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  if (_allowsFullScreen)
                                    Positioned(
                                      top: 10,
                                      right: 10,
                                      child: IconButton(
                                        tooltip: _fullScreen ? '退出全屏' : '全屏预览',
                                        onPressed: () => setState(
                                          () => _fullScreen = !_fullScreen,
                                        ),
                                        style: IconButton.styleFrom(
                                          backgroundColor: const Color(
                                            0xB3191817,
                                          ),
                                          foregroundColor: Colors.white,
                                          side: const BorderSide(
                                            color: Colors.white38,
                                          ),
                                        ),
                                        icon: Icon(
                                          _fullScreen
                                              ? Icons.fullscreen_exit_rounded
                                              : Icons.fullscreen_rounded,
                                        ),
                                      ),
                                    ),
                                ],
                              ),
                            ),
                          ),
                        ),
                        if (!_fullScreen) ...[
                          const SizedBox(height: T.space4),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: Text(
                              wallpaper.title,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              textAlign: TextAlign.left,
                              style: Theme.of(context).textTheme.titleLarge
                                  ?.copyWith(fontWeight: FontWeight.w700),
                            ),
                          ),
                          if (ios != null && ios.error != null)
                            Padding(
                              padding: const EdgeInsets.only(top: T.space2),
                              child: Text(
                                ios.error!,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.bodySmall,
                              ),
                            ),
                          if (ios == null &&
                              !wallpaper.isFree &&
                              ownershipError != null)
                            Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    ownershipError!,
                                    maxLines: 2,
                                    style: Theme.of(
                                      context,
                                    ).textTheme.bodySmall,
                                  ),
                                ),
                                TextButton(
                                  onPressed: _ownership,
                                  child: const Text('重新加载'),
                                ),
                              ],
                            ),
                          const SizedBox(height: T.space4),
                          QjPrimaryAction(
                            label: label,
                            accent: true,
                            loading: waitsForOwnership,
                            onPressed: canAct
                                ? () => _action(wallpaper, previewOption!)
                                : null,
                          ),
                        ],
                      ],
                    ),
                  );
                },
              ),
            ),
          ),
        ),
      ),
    ),
  );
}
