import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../privacy/policies.dart';

Future<void> copyCustomerEmail(BuildContext context) async {
  try {
    await Clipboard.setData(const ClipboardData(text: qingjingSupportEmail));
    if (context.mounted) _notice(context, '客服邮箱已复制');
  } catch (_) {
    if (context.mounted) _notice(context, '复制失败，请手动选择文字复制');
  }
}

Future<void> showCustomerServiceDialog(BuildContext context) =>
    showDialog<void>(
      context: context,
      barrierDismissible: true,
      builder: (dialogContext) => Dialog(
        insetPadding: const EdgeInsets.all(T.space4),
        backgroundColor: Colors.transparent,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: T.sizeContentMax,
            maxHeight: MediaQuery.sizeOf(dialogContext).height * 0.86,
          ),
          child: Material(
            color: T.colorSurface,
            borderRadius: BorderRadius.circular(T.radiusCard),
            clipBehavior: Clip.antiAlias,
            child: Padding(
              padding: const EdgeInsets.all(T.space4),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '联系客服',
                          style: Theme.of(dialogContext).textTheme.titleLarge,
                        ),
                      ),
                      IconButton(
                        tooltip: '关闭',
                        onPressed: () => Navigator.pop(dialogContext),
                        icon: const Icon(Icons.close_rounded),
                      ),
                    ],
                  ),
                  const SizedBox(height: T.space2),
                  Flexible(
                    child: SingleChildScrollView(
                      child: QjCustomerServiceCard(
                        onCopy: () => copyCustomerEmail(dialogContext),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );

void _notice(BuildContext context, String message) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
}
