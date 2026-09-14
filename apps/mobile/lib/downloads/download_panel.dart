import 'package:flutter/material.dart';
import 'download_manager.dart';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import '../detail/delivery.dart';

class DownloadPanel extends StatefulWidget {
  const DownloadPanel({
    super.key,
    required this.manager,
    required this.wallpaperId,
    required this.resourceType,
    this.playback,
    this.capabilities = const WallpaperCapabilities(
      platform: ClientPlatform.android,
    ),
  });
  final DownloadManager manager;
  final String wallpaperId, resourceType;
  final AndroidWallpaperPlayback? playback;
  final WallpaperCapabilities capabilities;
  @override
  State<DownloadPanel> createState() => _DownloadPanelState();
}

class _DownloadPanelState extends State<DownloadPanel> {
  String? installed;
  bool nativeBusy = false;
  String? nativeMessage;
  Future<void> _native({WallpaperTarget? target}) async {
    final id = installed,
        effect = effectForResource(widget.resourceType),
        playback = widget.playback;
    if (id == null || effect == null || playback == null || nativeBusy) return;
    setState(() {
      nativeBusy = true;
      nativeMessage = null;
    });
    try {
      final result = target == null
          ? await playback.open(id, effect)
          : await playback.apply(id, effect, target);
      if (mounted) {
        setState(
          () => nativeMessage =
              result.message ??
              switch (result.status) {
                OperationStatus.completed =>
                  target == null ? '预览已结束' : '系统已确认设置完成',
                OperationStatus.cancelled => '已取消',
                OperationStatus.unsupported => '当前手机不支持此操作',
                OperationStatus.unknown => '结果不可确认，请检查系统壁纸',
              },
        );
      }
    } catch (_) {
      if (mounted) setState(() => nativeMessage = '原生操作暂时不可用，请重试');
    } finally {
      if (mounted) setState(() => nativeBusy = false);
    }
  }

  @override
  void initState() {
    super.initState();
    _refresh();
    widget.manager.addListener(_changed);
  }

  Future<void> _refresh() async {
    try {
      final id = await widget.manager.current(
        widget.wallpaperId,
        widget.resourceType,
      );
      if (mounted) setState(() => installed = id);
    } catch (_) {}
  }

  void _changed() {
    if (!widget.manager.value.busy) _refresh();
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
      final busy = state.busy && own;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(installed == null ? '本地资源尚未安装' : '本地资源已安全安装'),
          if (busy) ...[
            const SizedBox(height: 8),
            LinearProgressIndicator(
              value: state.total > 0 ? state.received / state.total : null,
            ),
            Text(switch (state.status) {
              'verifying' => '正在校验资源…',
              'installing' => '正在安装资源…',
              'preparing' => '正在验证权益…',
              _ =>
                '正在下载 ${(state.received / 1024 / 1024).toStringAsFixed(1)} MB',
            }),
            TextButton(
              onPressed: state.status == 'installing'
                  ? null
                  : widget.manager.cancel,
              child: const Text('取消下载'),
            ),
          ],
          if (own && state.message != null) Text(state.message!),
          FilledButton.tonal(
            onPressed: state.busy || nativeBusy
                ? null
                : () => widget.manager.download(
                    widget.wallpaperId,
                    widget.resourceType,
                  ),
            child: Text(installed == null ? '下载资源（需已有权益）' : '重新下载或更新资源'),
          ),
          const Text('中断后重新申请票据并从头下载。更新失败保留原版。'),
          if (widget.playback != null && installed != null) ...[
            if (widget.capabilities.previewEffects.contains(
              effectForResource(widget.resourceType),
            ))
              OutlinedButton(
                onPressed: nativeBusy || state.busy ? null : () => _native(),
                child: const Text('全屏预览已安装资源'),
              ),
            ...WallpaperTarget.values
                .where(
                  (target) => widget.capabilities.canApply(
                    effectForResource(widget.resourceType)!,
                    target,
                  ),
                )
                .map(
                  (target) => FilledButton(
                    onPressed: nativeBusy || state.busy
                        ? null
                        : () => _native(target: target),
                    child: Text(
                      widget.resourceType == 'VIDEO'
                          ? '打开系统视频壁纸设置'
                          : widget.resourceType == 'LAYER_PARALLAX' &&
                                widget.capabilities.systemChoosesLiveTarget
                          ? '打开系统4D壁纸设置'
                          : '设为${targetLabel(target)}',
                    ),
                  ),
                ),
          ],
          if (nativeBusy) const LinearProgressIndicator(),
          if (nativeMessage != null) Text(nativeMessage!),
        ],
      );
    },
  );
}
