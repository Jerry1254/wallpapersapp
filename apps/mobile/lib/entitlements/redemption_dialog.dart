import 'package:flutter/material.dart';
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
  bool busy = false;
  String? message;
  Future<void> run(bool confirm) async {
    if (busy) return;
    setState(() => busy = true);
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
    if (mounted) {
      setState(() {
        busy = false;
        message = result;
      });
    }
  }

  @override
  void dispose() {
    code.clear();
    code.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('兑换壁纸'),
    content: SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text('成功兑换消耗 1 次额度；已拥有不重复扣减。权益保存在当前安装身份下。'),
          TextField(
            controller: code,
            enabled: !busy,
            obscureText: true,
            autocorrect: false,
            enableSuggestions: false,
            maxLength: 32,
            decoration: const InputDecoration(labelText: '20 位兑换码'),
          ),
          if (message != null) Text(message!),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: busy ? null : () => Navigator.pop(context),
        child: const Text('关闭'),
      ),
      TextButton(
        onPressed: busy ? null : () => run(true),
        child: const Text('确认原结果'),
      ),
      FilledButton(
        onPressed: busy ? null : () => run(false),
        child: Text(busy ? '处理中…' : '确认兑换'),
      ),
    ],
  );
}
