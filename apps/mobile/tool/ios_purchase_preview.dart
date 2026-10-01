import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:qingjing_wallpaper/design_system/qj_theme.dart';
import 'package:qingjing_wallpaper/entitlements/ios_acquisition.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';

// Independent local preview: no API URLs, delivery tickets, or real MP4s.
// Run with Runner-StoreKit and its local .storekit file. This entry point is
// deliberately separate from lib/main.dart and is blocked in release mode.
void main() {
  if (!kDebugMode) throw StateError('内购交互预览只允许 Debug 构建');
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(home: PurchasePreview()));
}

class PreviewApi implements IosAcquisitionApi {
  final free = <String>{}, paid = <String>{};
  IosAcquisitionState get snapshot => IosAcquisitionState(
    accountToken: '4e86cb85-94b2-49e0-bad0-a8d00b8cb0d9',
    products: const {
      '1': 'com.qingjing.bizhi.test.wallpaper.1',
      '2': 'com.qingjing.bizhi.test.wallpaper.2',
    },
    freeAllowance: free.isEmpty
        ? IosFreeAllowance.available
        : IosFreeAllowance.used,
    freeWallpaperIds: Set.of(free),
    purchasedWallpaperIds: Set.of(paid),
  );
  @override
  Future<IosAcquisitionState> state() async => snapshot;
  @override
  Future<IosAcquisitionState> claimFree(
    String wallpaperId,
    String requestId,
  ) async {
    if (free.isNotEmpty && !free.contains(wallpaperId)) {
      throw StateError('测试额度已使用');
    }
    free.add(wallpaperId);
    return snapshot;
  }

  @override
  Future<IosAcquisitionState> synchronize(
    IosStoreTransaction transaction,
  ) async {
    if (!kDebugMode || transaction.environment != 'XCODE') {
      throw StateError(
        'Only Xcode StoreKit transactions are accepted in this preview',
      );
    }
    final id = snapshot.products.entries
        .singleWhere((e) => e.value == transaction.productId)
        .key;
    if (transaction.revoked) {
      paid.remove(id);
    } else {
      paid.add(id);
    }
    return snapshot;
  }
}

/// Keep preview cache away from the real installation's entitlement cache.
class PreviewStore implements IosPurchaseStore {
  final native = const NativeIosPurchaseStore(testOnly: true);
  String? _cache;
  @override
  Future<String?> readCache() async => _cache;
  @override
  Future<void> writeCache(String value) async {
    _cache = value;
  }

  @override
  Future<List<IosStoreProduct>> products(Set<String> ids) =>
      native.products(ids);
  @override
  Future<IosPurchaseResult> purchase(String id, String token) =>
      native.purchase(id, token);
  @override
  Future<List<IosStoreTransaction>> transactions({
    bool restore = false,
  }) async => (await native.transactions(restore: restore))
      .where(
        (value) =>
            value.productId.startsWith('com.qingjing.bizhi.test.wallpaper.'),
      )
      .toList();
  @override
  Future<void> finish(String id) => native.finish(id);
  @override
  Future<void> observe(void Function(IosStoreTransaction) callback) =>
      native.observe(callback);
  @override
  Future<void> stopObserving() => native.stopObserving();
}

class PurchasePreview extends StatefulWidget {
  const PurchasePreview({super.key});
  @override
  State<PurchasePreview> createState() => _PurchasePreviewState();
}

class _PurchasePreviewState extends State<PurchasePreview> {
  late final flow = IosAcquisitionController(PreviewApi(), PreviewStore());
  String selected = '1', message = '免费额度为本次预览模拟；不会下载正式壁纸。';
  @override
  void initState() {
    super.initState();
    unawaited(flow.initialize());
  }

  Future<void> run(Future<String> Function() action) async {
    try {
      final value = await action();
      if (mounted) setState(() => message = value);
    } on IosAcquisitionNotice catch (notice) {
      if (mounted) setState(() => message = notice.message);
    }
  }

  @override
  void dispose() {
    flow.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Theme(
    data: QjTheme.light,
    child: Scaffold(
      appBar: AppBar(title: const Text('苹果内购测试 · 不扣费')),
      body: ListenableBuilder(
        listenable: flow,
        builder: (context, _) => Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            children: [
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: '1', label: Text('壁纸一')),
                  ButtonSegment(value: '2', label: Text('壁纸二')),
                ],
                selected: {selected},
                onSelectionChanged: (values) =>
                    setState(() => selected = values.single),
              ),
              const SizedBox(height: 24),
              Expanded(
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(24),
                  child: SvgPicture.asset(
                    'assets/ui-reference/${selected == '1' ? 'coast' : 'mountain'}.svg',
                    fit: BoxFit.cover,
                  ),
                ),
              ),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: flow.canAcquire(selected)
                    ? () => run(() async {
                        final success = await flow.acquire(selected);
                        return success ? '测试获取成功（未下载正式资源）' : '操作已取消，或资格已更新';
                      })
                    : null,
                child: Text(flow.label(selected)),
              ),
              TextButton(
                onPressed: flow.busy ? null : () => run(flow.restore),
                child: const Text('恢复购买'),
              ),
              Text(flow.error ?? message, textAlign: TextAlign.center),
            ],
          ),
        ),
      ),
    ),
  );
}
