import 'package:flutter/material.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import 'download_manager.dart';

class GlobalDownloadDialog extends StatefulWidget {
  const GlobalDownloadDialog({super.key, required this.manager});

  final DownloadManager manager;

  @override
  State<GlobalDownloadDialog> createState() => _GlobalDownloadDialogState();
}

class _GlobalDownloadDialogState extends State<GlobalDownloadDialog> {
  bool closing = false;

  @override
  void initState() {
    super.initState();
    widget.manager.addListener(_downloadChanged);
  }

  void _downloadChanged() {
    if (widget.manager.value.status != 'completed' || closing || !mounted) {
      return;
    }
    closing = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.of(context).pop();
    });
  }

  @override
  void dispose() {
    widget.manager.removeListener(_downloadChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<DownloadState>(
    valueListenable: widget.manager,
    builder: (context, state, _) {
      final failed =
          !state.busy && const {'failed', 'cancelled'}.contains(state.status);
      final progress = state.total > 0
          ? (state.received / state.total).clamp(0.0, 1.0)
          : null;
      final percent = progress == null ? null : (progress * 100).round();
      return PopScope(
        canPop: !state.busy,
        child: Dialog(
          insetPadding: const EdgeInsets.symmetric(horizontal: T.space5),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(T.radiusCard),
          ),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 390),
            child: Padding(
              padding: const EdgeInsets.all(T.space6),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 58,
                    height: 58,
                    decoration: BoxDecoration(
                      color: failed ? T.colorDangerSoft : T.colorAccentSoft,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Center(
                      child: QjIcon(
                        failed ? 'circle-alert' : 'download',
                        size: 28,
                        color: failed ? T.colorDanger : T.colorAccentStrong,
                      ),
                    ),
                  ),
                  const SizedBox(height: T.space4),
                  Text(
                    failed
                        ? '下载失败'
                        : state.afterRedemption
                        ? '兑换成功'
                        : '正在下载壁纸',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: T.space2),
                  Text(
                    failed
                        ? (state.message ?? '壁纸资源下载失败，请重试')
                        : '正在下载壁纸资源，请勿关闭或切换到别的 App',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: T.space5),
                  if (!failed) ...[
                    ClipRRect(
                      borderRadius: BorderRadius.circular(T.radiusPill),
                      child: LinearProgressIndicator(
                        value: progress,
                        minHeight: 8,
                        color: T.colorAccent,
                        backgroundColor: T.colorSurfaceStrong,
                      ),
                    ),
                    const SizedBox(height: T.space2),
                    Text(
                      percent == null ? '正在准备下载' : '下载中 $percent%',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ] else ...[
                    QjPrimaryAction(
                      label: '重新尝试',
                      onPressed: widget.manager.retry,
                    ),
                    TextButton(
                      onPressed: () {
                        widget.manager.dismissResult();
                        Navigator.pop(context);
                      },
                      child: const Text('关闭'),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
}
