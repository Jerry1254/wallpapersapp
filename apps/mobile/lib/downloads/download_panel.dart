import 'package:flutter/material.dart';
import 'download_manager.dart';

class DownloadPanel extends StatefulWidget {
  const DownloadPanel({
    super.key,
    required this.manager,
    required this.wallpaperId,
    required this.resourceType,
  });
  final DownloadManager manager;
  final String wallpaperId, resourceType;
  @override
  State<DownloadPanel> createState() => _DownloadPanelState();
}

class _DownloadPanelState extends State<DownloadPanel> {
  String? installed;
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
            onPressed: state.busy
                ? null
                : () => widget.manager.download(
                    widget.wallpaperId,
                    widget.resourceType,
                  ),
            child: Text(installed == null ? '下载资源（需已有权益）' : '重新下载或更新资源'),
          ),
          const Text('中断后重新申请票据并从头下载。更新失败保留原版；安装完成后仍需原生设置能力。'),
        ],
      );
    },
  );
}
