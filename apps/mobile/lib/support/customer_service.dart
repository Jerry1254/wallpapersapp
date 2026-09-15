import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const customerWechatId = 'qingjing_service';
const _customerQrAsset = 'assets/ui-reference/customer-service-qr.png';
const _channel = MethodChannel('qingjing/wallpaper_android');

Future<void> copyCustomerWechat(BuildContext context) async {
  try {
    await Clipboard.setData(const ClipboardData(text: customerWechatId));
    if (context.mounted) _notice(context, '客服微信已复制');
  } catch (_) {
    if (context.mounted) _notice(context, '复制失败，请手动选择文字复制');
  }
}

Future<void> previewCustomerQr(BuildContext context) => showDialog<void>(
  context: context,
  barrierColor: Colors.black87,
  builder: (dialogContext) => Stack(
    children: [
      Center(
        child: InteractiveViewer(
          minScale: 0.8,
          maxScale: 4,
          child: Image.asset(_customerQrAsset, width: 320, height: 320),
        ),
      ),
      Positioned(
        top: 12,
        right: 12,
        child: SafeArea(
          child: IconButton.filled(
            tooltip: '关闭',
            onPressed: () => Navigator.pop(dialogContext),
            icon: const Icon(Icons.close),
          ),
        ),
      ),
    ],
  ),
);

Future<void> saveCustomerQr(BuildContext context) async {
  try {
    final data = await rootBundle.load(_customerQrAsset);
    final bytes = Uint8List.sublistView(data);
    await _channel.invokeMethod<String>('saveCustomerQr', {'bytes': bytes});
    if (context.mounted) _notice(context, '二维码已保存，请检查相册');
  } catch (_) {
    if (context.mounted) _notice(context, '保存二维码失败，请重试');
  }
}

void _notice(BuildContext context, String message) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
}
