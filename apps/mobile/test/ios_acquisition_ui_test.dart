import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/catalog/catalog.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/detail/detail_screen.dart';
import 'package:qingjing_wallpaper/design_system/qj_theme.dart';
import 'package:qingjing_wallpaper/entitlements/entitlements_screen.dart';
import 'package:qingjing_wallpaper/entitlements/ios_acquisition.dart';
import 'package:qingjing_wallpaper/entitlements/redemption.dart';
import 'catalog_test.dart' show FakeCatalog;
import 'ios_acquisition_test.dart' show TestAcquisitionApi, TestPurchaseStore;

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

void main() {
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
      final flow = IosAcquisitionController(
        api,
        store,
        chinaReferencePrices: {'test.wallpaper.2': '¥1.00'},
      );
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
      expect(find.text('中国区参考价，实际付款以 Apple 确认页为准'), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNotNull,
      );
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      store.currencyCode = 'CNY';
      store.displayPrice = '¥6.00';
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
