import 'package:flutter/material.dart';

import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import '../entitlements/ios_acquisition.dart';

Future<bool> confirmIosExchange(
  BuildContext context, {
  required IosAcquisitionController acquisition,
  required String wallpaperId,
  required String title,
}) async {
  final quote = acquisition.exchangeQuote(wallpaperId);
  if (quote == null) {
    if (acquisition.needsExchangeConfirmation(wallpaperId)) {
      throw const IosAcquisitionNotice('兑换价格暂不可用，请稍后重试');
    }
    return true;
  }
  final confirmed = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
    builder: (context) => ListenableBuilder(
      listenable: acquisition,
      builder: (context, _) {
        final unchanged = acquisition.exchangeQuote(wallpaperId) == quote;
        return Padding(
          padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '兑换说明',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭兑换说明',
                    onPressed: () => Navigator.pop(context, false),
                    icon: const Icon(Icons.close_rounded),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Flexible(
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        title,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 20),
                      if (quote.credits != null) ...[
                        _ExchangeAmount('所需积分', '${quote.credits}个积分'),
                        const SizedBox(height: 12),
                      ],
                      _ExchangeAmount('本次付款', quote.amount),
                      const SizedBox(height: 20),
                      if (quote.credits != null) ...[
                        const Text('1积分＝1元，积分仅用于兑换壁纸。'),
                        const SizedBox(height: 12),
                      ],
                      const Text('点击“确认兑换”后，通过 Apple 完成付款。支付成功后自动下载到相册。'),
                      const SizedBox(height: 12),
                      const Text('下载后的壁纸可永久使用。已兑换壁纸可再次下载，无需重复兑换；其他壁纸需单独兑换。'),
                      if (quote.credits == null) ...[
                        const SizedBox(height: 12),
                        const Text('实际付款金额以 Apple 确认页为准。'),
                      ],
                      if (!unchanged) ...[
                        const SizedBox(height: 12),
                        const Text(
                          '价格或资格已更新，请关闭后重新确认。',
                          style: TextStyle(color: T.colorDanger),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),
              QjPrimaryAction(
                label: '确认兑换',
                accent: true,
                onPressed: unchanged
                    ? () => Navigator.pop(context, true)
                    : null,
              ),
            ],
          ),
        );
      },
    ),
  );
  if (confirmed != true) return false;
  if (acquisition.exchangeQuote(wallpaperId) != quote) {
    throw const IosAcquisitionNotice('价格或资格已更新，请重新确认兑换');
  }
  return true;
}

class _ExchangeAmount extends StatelessWidget {
  const _ExchangeAmount(this.label, this.value);
  final String label, value;
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(child: Text(label)),
      Text(
        value,
        style: Theme.of(
          context,
        ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
      ),
    ],
  );
}
