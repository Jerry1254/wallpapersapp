import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/detail/detail_screen.dart';
import 'package:qingjing_wallpaper/design_system/qj_theme.dart';
import 'package:qingjing_wallpaper/entitlements/entitlements_screen.dart';
import 'package:qingjing_wallpaper/entitlements/ios_acquisition.dart';
import 'package:qingjing_wallpaper/entitlements/redemption.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';
import 'catalog_test.dart' show FakeCatalog;
import 'ios_acquisition_test.dart' show TestAcquisitionApi, TestPurchaseStore;
import 'ios_credit_acquisition_test.dart' show CreditApi, CreditStore;

class AcquisitionCatalog extends FakeCatalog {
  @override
  Future<Wallpaper> detail(String id) async => Wallpaper.fromJson({
    'id': id,
    'title': '壁纸测试',
    'cover': {'contentUrl': '/cover'},
    'availableCapabilities': [
      {
        'deliveryPlatform': 'UNIVERSAL',
        'resourceType': 'STATIC_IMAGE',
        'placements': ['HOME'],
      },
    ],
  });
}

class FreeAcquisitionCatalog extends AcquisitionCatalog {
  @override
  Future<Wallpaper> detail(String id) async => Wallpaper.fromJson({
    'id': id,
    'title': '免费壁纸测试',
    'accessType': 'FREE',
    'cover': {'contentUrl': '/cover'},
    'availableCapabilities': [
      {
        'deliveryPlatform': 'UNIVERSAL',
        'resourceType': 'STATIC_IMAGE',
        'placements': ['HOME'],
      },
    ],
  });
}

class AcquisitionSessions extends DeviceSessionManager {
  AcquisitionSessions()
    : super(HttpDeviceTransport(Uri.parse('https://unused.invalid/api/v1')));
  int entitlementReads = 0;
  @override
  Future<Map<String, dynamic>> authenticated(
    String path, {
    String method = 'GET',
    String? body,
    bool signed = false,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    entitlementReads++;
    return {
      'items': [],
      'page': {'totalPages': 1},
    };
  }
}

class EmptyPending implements PendingStore {
  @override
  Future<PendingRedemption?> read() async => null;
  @override
  Future<void> write(PendingRedemption? value) async {}
}

class CancelOnceCreditApi extends CreditApi {
  int cancelAttempts = 0;
  @override
  Future<IosAcquisitionState> cancelCreditOrder(
    String id,
    Map<String, dynamic> identity,
  ) async {
    if (++cancelAttempts == 1) {
      throw StateError('Cancellation temporarily offline');
    }
    return snapshot;
  }
}

void main() {
  testWidgets('取消付款后清理订单失败，重试仍先确认新兑换', (tester) async {
    final api = CancelOnceCreditApi();
    final store = CreditStore()..next = const IosPurchaseResult('CANCELLED');
    final flow = IosAcquisitionController(api, store);
    addTearDown(flow.dispose);
    await flow.initialize();
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        home: DetailScreen(
          repository: AcquisitionCatalog(),
          id: '1',
          iosAcquisition: flow,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('18个积分兑换壁纸'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认兑换'));
    await tester.pumpAndSettle();
    expect(store.purchases, 1);
    expect(flow.owns('1'), isFalse);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    store.next = const IosPurchaseResult('PURCHASED');
    await tester.tap(find.text('确认购买结果'));
    await tester.pumpAndSettle();
    expect(find.text('兑换说明'), findsOneWidget);
    expect(store.purchases, 1);
    await tester.tap(find.text('确认兑换'));
    await tester.pumpAndSettle();
    expect(store.purchases, 2);
    expect(find.text('再次下载'), findsOneWidget);
  });

  testWidgets('积分说明只在兑换弹窗出现，取消不付款，确认后付款并获取权益', (tester) async {
    final api = CreditApi(), store = CreditStore();
    final flow = IosAcquisitionController(api, store);
    addTearDown(flow.dispose);
    await flow.initialize();
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        home: DetailScreen(
          repository: AcquisitionCatalog(),
          id: '1',
          iosAcquisition: flow,
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('1积分＝1元'), findsNothing);
    expect(find.byTooltip('全屏预览'), findsNothing);
    await tester.tap(find.text('18个积分兑换壁纸'));
    await tester.pumpAndSettle();
    expect(find.text('兑换说明'), findsOneWidget);
    expect(find.text('18个积分'), findsOneWidget);
    expect(find.text('¥18.00'), findsOneWidget);
    expect(find.textContaining('1积分＝1元'), findsOneWidget);
    expect(find.textContaining('永久使用'), findsOneWidget);
    expect(store.purchases, 0);
    expect(api.orderCalls, 0);
    await tester.tap(find.byTooltip('关闭兑换说明'));
    await tester.pumpAndSettle();
    expect(store.purchases, 0);
    await tester.tap(find.text('18个积分兑换壁纸'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认兑换'));
    await tester.pumpAndSettle();
    expect(store.purchases, 1);
    expect(api.orderCalls, 1);
    expect(find.text('再次下载'), findsOneWidget);
    await tester.tap(find.text('再次下载'));
    await tester.pumpAndSettle();
    expect(find.text('兑换说明'), findsNothing);
    expect(store.purchases, 1);
  });

  testWidgets('弹窗期间积分变化禁止用旧价格确认', (tester) async {
    final api = CreditApi(), store = CreditStore();
    final flow = IosAcquisitionController(api, store);
    addTearDown(flow.dispose);
    await flow.initialize();
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        home: DetailScreen(
          repository: AcquisitionCatalog(),
          id: '1',
          iosAcquisition: flow,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('18个积分兑换壁纸'));
    await tester.pumpAndSettle();
    api.offers['1'] = const IosCreditOffer(20, 2, 10, true);
    await flow.refreshPrices();
    await tester.pumpAndSettle();
    expect(find.text('价格或资格已更新，请关闭后重新确认。'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '确认兑换'))
          .onPressed,
      isNull,
    );
    expect(store.purchases, 0);
    await tester.tap(find.byTooltip('关闭兑换说明'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('20个积分兑换壁纸'));
    await tester.pumpAndSettle();
    expect(find.text('¥20.00'), findsOneWidget);
    await tester.tap(find.text('确认兑换'));
    await tester.pumpAndSettle();
    expect(store.purchasedQuantity, 10);
    expect(store.purchases, 1);
  });

  testWidgets('尚有首免额度但当前壁纸不适用时仍须确认付款', (tester) async {
    final api = CreditApi()..allowance = IosFreeAllowance.available;
    final store = CreditStore();
    final flow = IosAcquisitionController(api, store);
    addTearDown(flow.dispose);
    await flow.initialize();
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        home: DetailScreen(
          repository: AcquisitionCatalog(),
          id: '2',
          iosAcquisition: flow,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('20个积分兑换壁纸'));
    await tester.pumpAndSettle();
    expect(find.text('兑换说明'), findsOneWidget);
    expect(store.purchases, 0);
    expect(api.claims, isEmpty);
  });

  testWidgets(
    'iOS first free acquisition updates detail button without a redemption sheet',
    (tester) async {
      final api = TestAcquisitionApi(), store = TestPurchaseStore();
      final flow = IosAcquisitionController(api, store);
      addTearDown(flow.dispose);
      await flow.initialize();
      await tester.pumpWidget(
        MaterialApp(
          theme: QjTheme.light,
          home: DetailScreen(
            repository: AcquisitionCatalog(),
            id: '1',
            iosAcquisition: flow,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('首次免费获取'), findsOneWidget);
      expect(find.text('兑换并下载'), findsNothing);
      await tester.tap(find.text('首次免费获取'));
      await tester.pumpAndSettle();
      expect(find.text('再次下载'), findsOneWidget);
      expect(api.claims, hasLength(1));
      expect(api.stateCalls, 1);
    },
  );

  testWidgets(
    'iOS used quota displays Apple price while other platforms keep their old flow',
    (tester) async {
      final api = TestAcquisitionApi()..allowance = IosFreeAllowance.used;
      final flow = IosAcquisitionController(api, TestPurchaseStore());
      addTearDown(flow.dispose);
      await flow.initialize();
      await tester.pumpWidget(
        MaterialApp(
          theme: QjTheme.light,
          home: DetailScreen(
            repository: AcquisitionCatalog(),
            id: '2',
            iosAcquisition: flow,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('¥1.00 购买并下载'), findsOneWidget);
      await tester.pumpWidget(
        MaterialApp(
          theme: QjTheme.light,
          home: DetailScreen(repository: AcquisitionCatalog(), id: '2'),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('¥1.00 购买并下载'), findsNothing);
      expect(find.text('下载壁纸'), findsOneWidget);
    },
  );

  testWidgets(
    'iOS unmapped paid wallpaper is visibly blocked before first-free claim',
    (tester) async {
      final api = TestAcquisitionApi()..products.remove('1');
      final flow = IosAcquisitionController(api, TestPurchaseStore());
      addTearDown(flow.dispose);
      await flow.initialize();
      await tester.pumpWidget(
        MaterialApp(
          theme: QjTheme.light,
          home: DetailScreen(
            repository: AcquisitionCatalog(),
            id: '1',
            iosAcquisition: flow,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('商品尚未配置'), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull,
      );
      expect(api.claims, isEmpty);
    },
  );

  testWidgets(
    'international storefront displays RMB reference without blocking Apple purchase',
    (tester) async {
      final api = TestAcquisitionApi()..allowance = IosFreeAllowance.used;
      final store = TestPurchaseStore()
        ..currencyCode = 'USD'
        ..displayPrice = 'US\$0.99';
      final flow = IosAcquisitionController(api, store);
      addTearDown(flow.dispose);
      await flow.initialize();
      await tester.pumpWidget(
        MaterialApp(
          theme: QjTheme.light,
          home: DetailScreen(
            repository: AcquisitionCatalog(),
            id: '2',
            iosAcquisition: flow,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('US\$0.99 购买并下载'), findsNothing);
      expect(find.text('¥1.00 购买并下载'), findsOneWidget);
      expect(find.text('中国区参考价，实际付款以 Apple 确认页为准'), findsNothing);
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNotNull,
      );
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      store.currencyCode = 'CNY';
      store.displayPrice = '¥6.00';
      api.prices['test.wallpaper.2'] = '6.00';
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('¥6.00 购买并下载'), findsOneWidget);
      expect(find.text('中国区参考价，实际付款以 Apple 确认页为准'), findsNothing);
      expect(api.stateCalls, 1);
      expect(store.synchronization, [false]);
    },
  );

  testWidgets('iOS FREE wallpaper bypasses first-free acquisition', (
    tester,
  ) async {
    final api = TestAcquisitionApi(), store = TestPurchaseStore();
    final flow = IosAcquisitionController(api, store);
    addTearDown(flow.dispose);
    await flow.initialize();
    await tester.pumpWidget(
      MaterialApp(
        theme: QjTheme.light,
        home: DetailScreen(
          repository: FreeAcquisitionCatalog(),
          id: '1',
          iosAcquisition: flow,
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('下载壁纸'), findsOneWidget);
    expect(find.text('首次免费获取'), findsNothing);
    await tester.tap(find.text('下载壁纸'));
    await tester.pumpAndSettle();
    expect(api.claims, isEmpty);
    expect(store.purchases, 0);
  });

  testWidgets(
    'My screen places Restore beside acquired wallpapers and syncs only on tap',
    (tester) async {
      final store = TestPurchaseStore();
      final flow = IosAcquisitionController(TestAcquisitionApi(), store);
      addTearDown(flow.dispose);
      await flow.initialize();
      final sessions = AcquisitionSessions();
      await tester.pumpWidget(
        MaterialApp(
          theme: QjTheme.light,
          home: Scaffold(
            body: EntitlementsScreen(
              sessions: sessions,
              catalog: AcquisitionCatalog(),
              redemptions: RedemptionCoordinator(
                SessionRedemptionApi(sessions),
                EmptyPending(),
              ),
              iosAcquisition: flow,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('恢复购买'), findsOneWidget);
      expect(store.synchronization, [false]);
      await tester.ensureVisible(find.text('恢复购买'));
      await tester.tap(find.text('恢复购买'));
      await tester.pumpAndSettle();
      expect(store.synchronization, [false, true]);
      expect(find.text('当前苹果账户没有可恢复的购买'), findsOneWidget);
    },
  );
}
