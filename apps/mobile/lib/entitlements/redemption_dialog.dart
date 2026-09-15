import 'package:flutter/material.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import 'redemption.dart';

class RedemptionDialog extends StatefulWidget {
  const RedemptionDialog({
    super.key,
    required this.coordinator,
    required this.wallpaperId,
  });
  final RedemptionCoordinator coordinator;
  final String wallpaperId;
  @override
  State<RedemptionDialog> createState() => _RedemptionDialogState();
}

class _RedemptionDialogState extends State<RedemptionDialog> {
  final code = TextEditingController();
  bool busy = false, pending = false, success = false;
  String? message;
  @override
  void initState() {
    super.initState();
    _pending();
  }

  Future<void> _pending() async {
    try {
      final value = await widget.coordinator.store.read();
      if (mounted) setState(() => pending = value != null);
    } catch (_) {}
  }

  Future<void> run(bool confirm) async {
    if (busy) return;
    setState(() {
      busy = true;
      message = null;
    });
    String result;
    try {
      result = confirm
          ? await widget.coordinator.confirm()
          : await widget.coordinator.redeem(widget.wallpaperId, code.text);
    } on RedemptionNotice catch (e) {
      result = e.message;
    } catch (_) {
      result = '兑换服务暂不可用，请重试';
    }
    if (!mounted) return;
    final granted = result.startsWith('兑换成功') || result.startsWith('已拥有');
    setState(() {
      busy = false;
      message = result;
      success = granted;
      if (granted) {
        pending = false;
        code.clear();
      }
    });
  }

  @override
  void dispose() {
    code.clear();
    code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => QjSheet(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: T.colorAccentSoft,
                borderRadius: BorderRadius.circular(15),
              ),
              child: const Center(
                child: QjIcon(
                  'key-round',
                  size: 21,
                  color: T.colorAccentStrong,
                ),
              ),
            ),
            const SizedBox(width: T.space3),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('兑换这张壁纸', style: Theme.of(context).textTheme.titleLarge),
                  Text(
                    '输入客服发送的兑换码',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: T.space5),
        Text(
          '兑换码',
          style: Theme.of(
            context,
          ).textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 7),
        TextField(
          controller: code,
          enabled: !busy && !success,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          maxLength: 32,
          textCapitalization: TextCapitalization.characters,
          decoration: const InputDecoration(
            hintText: '请输入兑换码',
            counterText: '',
          ),
        ),
        if (message != null)
          Padding(
            padding: const EdgeInsets.only(top: 9),
            child: Text(
              message!,
              style: QjTheme.type(
                12,
                FontWeight.w600,
                T.lineHeightCaption,
                success ? T.colorSuccess : T.colorDanger,
              ),
            ),
          ),
        if (pending) ...[
          const SizedBox(height: T.space4),
          QjPrimaryAction(
            label: '确认兑换结果',
            loading: busy,
            onPressed: () => run(true),
          ),
        ],
        const SizedBox(height: T.space4),
        QjPrimaryAction(
          label: success
              ? '下载壁纸'
              : pending
              ? '使用原码重试本次兑换'
              : '验证并兑换',
          loading: busy,
          onPressed: success
              ? () => Navigator.pop(context, true)
              : () => run(false),
        ),
      ],
    ),
  );
}
