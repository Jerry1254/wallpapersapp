import 'package:flutter/material.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import 'download_manager.dart';

Future<WallpaperEffect?> showWallpaperEffectPicker(
  BuildContext context,
  List<WallpaperEffect> effects,
) {
  if (effects.length == 1) return Future.value(effects.first);
  return showModalBottomSheet<WallpaperEffect>(
    context: context,
    useSafeArea: true,
    constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
    builder: (sheetContext) => QjSheet(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('选择设置方式', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 7),
          Text(
            '同一份壁纸权益包含动态和静态形式，本次只下载你选择的资源。',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: T.space4),
          for (final effect in effects)
            Padding(
              padding: const EdgeInsets.only(bottom: T.space2),
              child: Semantics(
                button: true,
                label: _effectTitle(effect),
                child: InkWell(
                  borderRadius: BorderRadius.circular(T.radiusControl),
                  onTap: () => Navigator.pop(sheetContext, effect),
                  child: Container(
                    constraints: const BoxConstraints(minHeight: 72),
                    padding: const EdgeInsets.symmetric(
                      horizontal: T.space4,
                      vertical: T.space3,
                    ),
                    decoration: BoxDecoration(
                      color: T.colorSurfaceMuted,
                      border: Border.all(color: T.colorOutline),
                      borderRadius: BorderRadius.circular(T.radiusControl),
                    ),
                    child: Row(
                      children: [
                        Container(
                          width: 44,
                          height: 44,
                          decoration: BoxDecoration(
                            color: T.colorSurfaceStrong,
                            borderRadius: BorderRadius.circular(14),
                          ),
                          child: Center(
                            child: QjIcon(
                              _effectIcon(effect),
                              color: T.colorInkSoft,
                            ),
                          ),
                        ),
                        const SizedBox(width: T.space3),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                _effectTitle(effect),
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                              Text(
                                _effectDescription(effect),
                                style: Theme.of(context).textTheme.bodySmall,
                              ),
                            ],
                          ),
                        ),
                        const QjIcon('chevron-right', color: T.colorMutedInk),
                      ],
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    ),
  );
}

String _effectTitle(WallpaperEffect effect) => switch (effect) {
  WallpaperEffect.video => '动态壁纸',
  WallpaperEffect.staticImage => '静态壁纸',
  WallpaperEffect.parallax => '4D 壁纸',
};

String _effectDescription(WallpaperEffect effect) => switch (effect) {
  WallpaperEffect.video => '播放上传的动态原资源',
  WallpaperEffect.staticImage => '使用高清静态原图',
  WallpaperEffect.parallax => '随手机倾斜产生分层视差',
};

String _effectIcon(WallpaperEffect effect) => switch (effect) {
  WallpaperEffect.video => 'play-square',
  WallpaperEffect.staticImage => 'image',
  WallpaperEffect.parallax => 'layers-3',
};

class DownloadPanel extends StatefulWidget {
  const DownloadPanel({
    super.key,
    required this.manager,
    required this.wallpaperId,
    required this.deliveryPlatform,
    required this.resourceType,
    this.playback,
    this.autoStart = false,
    this.onReady,
  });
  final DownloadManager manager;
  final String wallpaperId, deliveryPlatform, resourceType;
  final AndroidWallpaperPlayback? playback;
  final bool autoStart;
  final ValueChanged<String>? onReady;
  @override
  State<DownloadPanel> createState() => _DownloadPanelState();
}

class _DownloadPanelState extends State<DownloadPanel> {
  String? installed;
  bool checked = false;
  @override
  void initState() {
    super.initState();
    widget.manager.addListener(_changed);
    _refresh();
  }

  Future<void> _refresh() async {
    try {
      final value = await widget.manager.current(
        widget.wallpaperId,
        widget.resourceType,
      );
      if (!mounted) return;
      setState(() {
        installed = value;
        checked = true;
      });
      if (value == null && widget.autoStart && !widget.manager.value.busy) {
        widget.manager.download(
          widget.wallpaperId,
          widget.deliveryPlatform,
          widget.resourceType,
        );
      }
    } catch (_) {
      if (mounted) setState(() => checked = true);
    }
  }

  void _changed() {
    final state = widget.manager.value;
    if (state.wallpaperId == widget.wallpaperId &&
        state.resourceType == widget.resourceType &&
        state.status == 'completed') {
      setState(() => installed = state.installedId);
    }
  }

  @override
  void dispose() {
    widget.manager.removeListener(_changed);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<DownloadState>(
    valueListenable: widget.manager,
    builder: (context, state, _) {
      final own =
          state.wallpaperId == widget.wallpaperId &&
          state.resourceType == widget.resourceType;
      final failed = own && ['failed', 'cancelled'].contains(state.status);
      final success = installed != null || (own && state.status == 'completed');
      final verifying =
          own && ['verifying', 'installing'].contains(state.status);
      final busy = own && state.busy;
      final progress = state.total > 0
          ? (state.received / state.total).clamp(0.0, 1.0)
          : 0.0;
      final title = !checked || busy
          ? (verifying ? '正在校验资源' : '正在下载壁纸')
          : success
          ? '壁纸下载完成'
          : failed
          ? '下载失败'
          : '正在下载壁纸';
      final description = !checked || busy
          ? (verifying ? '确认文件完整性与资源签名' : '下载进度 ${(progress * 100).round()}%')
          : success
          ? '资源已安全保存到当前设备'
          : failed
          ? (state.message ?? '网络中断，请重新尝试')
          : '正在准备安全下载';
      final icon = success
          ? 'check'
          : failed
          ? 'circle-alert'
          : verifying
          ? 'shield-check'
          : 'download';
      final tone = success
          ? (T.colorSuccessSoft, T.colorSuccess)
          : failed
          ? (T.colorDangerSoft, T.colorDanger)
          : (T.colorAccentSoft, T.colorAccentStrong);
      return QjSheet(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 58,
              height: 58,
              decoration: BoxDecoration(
                color: tone.$1,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Center(child: QjIcon(icon, size: 28, color: tone.$2)),
            ),
            const SizedBox(height: T.space4),
            Text(title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 7),
            Text(
              description,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: T.space5),
            if (!success && !failed) ...[
              ClipRRect(
                borderRadius: BorderRadius.circular(T.radiusPill),
                child: LinearProgressIndicator(
                  value: progress,
                  minHeight: 8,
                  color: T.colorAccent,
                  backgroundColor: T.colorSurfaceStrong,
                ),
              ),
              if (busy && state.status != 'installing')
                TextButton(
                  onPressed: widget.manager.cancel,
                  child: const Text('取消下载'),
                ),
            ],
            if (success)
              QjPrimaryAction(
                label: '设置壁纸',
                accent: true,
                onPressed: () =>
                    widget.onReady?.call(installed ?? state.installedId!),
              ),
            if (failed)
              QjPrimaryAction(
                label: '重新尝试',
                onPressed: () => widget.manager.download(
                  widget.wallpaperId,
                  widget.deliveryPlatform,
                  widget.resourceType,
                ),
              ),
          ],
        ),
      );
    },
  );
}

class WallpaperTargetSheet extends StatefulWidget {
  const WallpaperTargetSheet({
    super.key,
    required this.installedId,
    required this.effect,
    required this.playback,
    required this.placements,
  });
  final String installedId;
  final WallpaperEffect effect;
  final AndroidWallpaperPlayback playback;
  final Set<String> placements;
  @override
  State<WallpaperTargetSheet> createState() => _WallpaperTargetSheetState();
}

class _WallpaperTargetSheetState extends State<WallpaperTargetSheet> {
  WallpaperTarget? target;
  bool busy = false, success = false, accepted = false;
  String? acceptedMessage;
  String? failure;
  bool get systemChoosesTarget => widget.effect != WallpaperEffect.staticImage;

  List<(WallpaperTarget, String, String, String)> get options {
    if (systemChoosesTarget) {
      return [(WallpaperTarget.home, '打开系统设置', '最终设置位置由手机系统确认', 'smartphone')];
    }
    return [
      if (widget.placements.contains('HOME'))
        (WallpaperTarget.home, '桌面壁纸', '显示在手机桌面', 'panels-top-left'),
      if (widget.placements.contains('LOCK'))
        (WallpaperTarget.lock, '锁屏壁纸', '显示在锁屏界面', 'lock-keyhole'),
      if (widget.placements.containsAll({'HOME', 'LOCK'}))
        (WallpaperTarget.both, '桌面和锁屏', '两处使用同一张壁纸', 'smartphone'),
    ];
  }

  @override
  void initState() {
    super.initState();
    final values = options;
    target = values.any((e) => e.$1 == WallpaperTarget.both)
        ? WallpaperTarget.both
        : values.firstOrNull?.$1;
  }

  void _readResult(PlatformResult<void> result) {
    success = result.status == OperationStatus.completed;
    accepted = result.status == OperationStatus.accepted;
    acceptedMessage = accepted ? result.message : null;
    failure = success || accepted
        ? null
        : result.message ??
              switch (result.status) {
                OperationStatus.cancelled => '已取消',
                OperationStatus.unsupported => '您的手机不支持此壁纸，请尝试切换其他类型',
                OperationStatus.unknown => '结果不可确认，请检查系统壁纸',
                OperationStatus.accepted => '系统已接受设置',
                OperationStatus.completed => '壁纸设置成功',
              };
  }

  Future<void> apply() async {
    if (target == null || busy) return;
    setState(() {
      busy = true;
      accepted = false;
      acceptedMessage = null;
      failure = null;
    });
    PlatformResult<void> result;
    try {
      result = await widget.playback.apply(
        widget.installedId,
        widget.effect,
        target!,
      );
    } catch (_) {
      result = const PlatformResult(
        OperationStatus.unsupported,
        message: '您的手机不支持此壁纸，请尝试切换其他类型',
      );
    }
    if (!mounted) return;
    setState(() {
      busy = false;
      _readResult(result);
    });
  }

  @override
  Widget build(BuildContext context) {
    final positive = success || accepted;
    return QjSheet(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            success
                ? '壁纸设置成功'
                : accepted
                ? '系统已接受设置'
                : '设置到哪里',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 4),
          Text(
            success
                ? '请返回桌面观看效果'
                : accepted
                ? (acceptedMessage ?? '请到桌面或锁屏查看效果')
                : systemChoosesTarget
                ? '由手机系统完成最终设置'
                : '请选择壁纸设置位置',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: T.space4),
          if (positive)
            Container(
              constraints: const BoxConstraints(minHeight: 148),
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: T.colorSuccessSoft,
                borderRadius: BorderRadius.circular(T.radiusCard),
              ),
              child: const QjIcon('check', size: 52, color: T.colorSuccess),
            )
          else if (failure != null)
            Container(
              constraints: const BoxConstraints(minHeight: 148),
              padding: const EdgeInsets.all(T.space4),
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: T.colorDangerSoft,
                borderRadius: BorderRadius.circular(T.radiusCard),
              ),
              child: Text(
                failure!,
                textAlign: TextAlign.center,
                style: QjTheme.type(
                  13,
                  FontWeight.w500,
                  T.lineHeightBody,
                  T.colorDanger,
                ),
              ),
            )
          else if (!systemChoosesTarget)
            for (final item in options)
              Padding(
                padding: const EdgeInsets.only(bottom: T.space2),
                child: Semantics(
                  button: true,
                  selected: target == item.$1,
                  child: InkWell(
                    onTap: () => setState(() => target = item.$1),
                    borderRadius: BorderRadius.circular(T.radiusControl),
                    child: Container(
                      constraints: const BoxConstraints(minHeight: 68),
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                      decoration: BoxDecoration(
                        color: target == item.$1
                            ? T.colorSurface
                            : T.colorSurfaceMuted,
                        border: Border.all(
                          color: target == item.$1
                              ? T.colorAccent
                              : T.colorOutline,
                        ),
                        borderRadius: BorderRadius.circular(T.radiusControl),
                        boxShadow: target == item.$1
                            ? const [T.shadowSoft]
                            : null,
                      ),
                      child: Row(
                        children: [
                          Container(
                            width: 42,
                            height: 42,
                            decoration: BoxDecoration(
                              color: T.colorSurfaceStrong,
                              borderRadius: BorderRadius.circular(14),
                            ),
                            child: Center(
                              child: QjIcon(item.$4, color: T.colorInkSoft),
                            ),
                          ),
                          const SizedBox(width: T.space3),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  item.$2,
                                  style: Theme.of(
                                    context,
                                  ).textTheme.titleMedium,
                                ),
                                Text(
                                  item.$3,
                                  style: Theme.of(context).textTheme.bodySmall,
                                ),
                              ],
                            ),
                          ),
                          if (target == item.$1)
                            const QjIcon(
                              'check',
                              size: 16,
                              color: T.colorAccentStrong,
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
          const SizedBox(height: T.space5),
          QjPrimaryAction(
            label: positive
                ? '完成'
                : failure != null
                ? '重新尝试'
                : '确认设置',
            accent: positive,
            loading: busy,
            onPressed: positive
                ? () => Navigator.pop(context)
                : failure != null && !systemChoosesTarget
                ? () => setState(() => failure = null)
                : apply,
          ),
        ],
      ),
    );
  }
}
