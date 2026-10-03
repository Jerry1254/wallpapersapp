import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/entitlements/ios_acquisition.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';
import 'ios_acquisition_test.dart'
    show TestAcquisitionApi, TestPurchaseStore, installationId, accountToken;

class CreditApi extends TestAcquisitionApi implements IosCreditAcquisitionApi {
  CreditApi() {
    allowance = IosFreeAllowance.used;
  }
  final offers = {
    '1': const IosCreditOffer(18, 2, 9, true),
    '2': const IosCreditOffer(20, 2, 10, true),
  };
  final mappings = {
    '1': 'com.qingjing.bizhi.credits.2',
    '2': 'com.qingjing.bizhi.credits.2',
  };
  int orderCalls = 0, restoreCalls = 0;
  String? orderedWall;
  bool paymentAllowed = true;
  Object? confirmationFailure;
  bool fulfilledBeforeFailure = false;
  @override
  IosAcquisitionState get snapshot => IosAcquisitionState(
    installationId: installationId,
    accountToken: accountToken,
    freeGeneration: 0,
    products: mappings,
    creditOffers: offers,
    freeEligible: {'1': true, '2': false},
    freeAllowance: allowance,
    purchasedWallpaperIds: Set.of(paid),
    freeWallpaperIds: Set.of(free),
  );
  @override
  Future<IosProductCatalogue> productCatalogue() async => snapshot.catalogue;
  @override
  Future<IosCreditOrder> creditOrder(
    String id,
    Map<String, dynamic> identity,
  ) async {
    orderCalls++;
    orderedWall = id;
    final offer = offers[id]!;
    return IosCreditOrder({
      'orderId': installationId,
      'wallpaperId': id,
      'productId': mappings[id],
      'packCredits': offer.packCredits,
      'quantity': offer.quantity,
      'credits': offer.credits,
      'amount': '${offer.credits}.00',
      'accountToken': accountToken,
      'priceVersion': 1,
      'status': 'OPEN',
      'paymentAllowed': paymentAllowed,
    });
  }

  @override
  Future<IosAcquisitionState> cancelCreditOrder(
    String id,
    Map<String, dynamic> identity,
  ) async => snapshot;
  @override
  Future<IosAcquisitionState> synchronize(
    IosStoreTransaction transaction,
  ) async {
    purchaseCalls++;
    if (rejectPurchase) throw StateError('Offline after payment');
    if (confirmationFailure != null) {
      if (fulfilledBeforeFailure) paid.add(orderedWall!);
      throw confirmationFailure!;
    }
    if (transaction.revoked) {
      paid.remove(orderedWall!);
    } else {
      paid.add(orderedWall!);
    }
    return snapshot;
  }

  @override
  Future<IosAcquisitionState> restoreCredits(
    Map<String, dynamic> identity,
  ) async {
    restoreCalls++;
    return snapshot;
  }
}

class CreditStore extends TestPurchaseStore implements IosCreditPurchaseStore {
  int? purchasedQuantity, purchasedPack;
  bool emitPaymentUpdate = false;
  @override
  Future<Map<String, dynamic>> appIdentity({bool refresh = false}) async => {
    'signedAppTransaction': 'signed-app',
    'deviceVerificationId': installationId,
  };
  @override
  Future<List<IosStoreProduct>> products(Set<String> ids) async => [
    for (final id in ids)
      IosStoreProduct(
        id,
        '¥2',
        currencyCode: currencyCode,
        price: '2',
        productType: 'CONSUMABLE',
      ),
  ];
  @override
  Future<IosPurchaseResult> purchaseCredits(
    String id,
    String token, {
    required int quantity,
    required int packCredits,
  }) async {
    expect(jsonDecode(cache!)['pendingCredit']['accountToken'], token);
    purchases++;
    purchasedQuantity = quantity;
    purchasedPack = packCredits;
    if (next.status != 'PURCHASED') return next;
    final transaction = IosStoreTransaction(
      id: 'credit-transaction',
      productId: id,
      signedTransaction: 'signed-txn',
      signedAppTransaction: 'signed-app',
      deviceVerificationId: installationId,
      environment: 'SANDBOX',
      productType: 'CONSUMABLE',
      quantity: quantity,
      accountToken: token,
    );
    unfinished.add(transaction);
    if (emitPaymentUpdate) listener?.call(transaction);
    return IosPurchaseResult('PURCHASED', transaction);
  }
}

class UnresponsivePriceStore extends CreditStore {
  final query = Completer<List<IosStoreProduct>>();
  @override
  Future<List<IosStoreProduct>> products(Set<String> ids) => query.future;
}

class UnresponsiveFinishStore extends CreditStore {
  final completion = Completer<void>();
  @override
  Future<void> finish(String id) => completion.future;
}

void main() {
  test(
    'shared SKU retains wallpaper price and pays exact quantity once',
    () async {
      final api = CreditApi(), store = CreditStore();
      final controller = IosAcquisitionController(api, store);
      await controller.initialize();
      expect(controller.label('1'), '18个积分兑换壁纸');
      expect(controller.label('2'), '20个积分兑换壁纸');
      expect(controller.priceNote('1'), '1积分＝1元');
      expect(
        IosAcquisitionState.fromJson(
          api.snapshot.toJson(),
        ).creditOffers['2']!.credits,
        20,
      );
      expect(await controller.acquire('2'), true);
      expect(store.purchasedQuantity, 10);
      expect(store.purchasedPack, 2);
      expect(controller.owns('2'), true);
      expect(controller.owns('1'), false);
      expect(store.finished, ['credit-transaction']);
      expect(await controller.acquire('2'), true);
      expect(store.purchases, 1);
      controller.dispose();
    },
  );
  test(
    'API failure retains unfinished payment; retry never charges twice',
    () async {
      final api = CreditApi()..rejectPurchase = true, store = CreditStore();
      final controller = IosAcquisitionController(api, store);
      await controller.initialize();
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(store.finished, isEmpty);
      expect(store.purchases, 1);
      for (var repeat = 0; repeat < 10; repeat++) {
        store.listener?.call(store.unfinished.single);
      }
      await Future<void>.delayed(Duration.zero);
      expect(api.purchaseCalls, 1);
      expect(controller.busy, false);
      expect(controller.label('1'), '确认购买结果');
      controller.error = '付款结果尚未确认，请恢复购买；无需再次付款';
      api.rejectPurchase = false;
      expect(await controller.acquire('1'), true);
      expect(store.purchases, 1);
      expect(store.finished, ['credit-transaction']);
      expect(controller.error, isNull);
      expect(
        jsonDecode(store.cache!).containsKey('lastPurchaseFailure'),
        false,
      );
      controller.dispose();
    },
  );
  testWidgets('unresponsive Apple price query cannot keep eligibility busy', (
    tester,
  ) async {
    final api = CreditApi(), store = UnresponsivePriceStore();
    final controller = IosAcquisitionController(api, store);
    addTearDown(controller.dispose);
    final initialization = controller.initialize();
    await tester.pump();
    expect(controller.busy, true);
    await tester.pump(const Duration(seconds: 21));
    await initialization;
    expect(controller.busy, false);
    expect(controller.ready, true);
    expect(controller.label('1'), 'App Store 暂不可购买');
    expect(store.purchases, 0);
  });
  test(
    'lost confirmation response reads back paid rights without another charge',
    () async {
      final api = CreditApi()
        ..confirmationFailure = const DeviceApiError(0, 'NETWORK_TIMEOUT')
        ..fulfilledBeforeFailure = true;
      final store = CreditStore();
      final controller = IosAcquisitionController(api, store);
      await controller.initialize();
      expect(await controller.acquire('1'), true);
      expect(api.restoreCalls, 1);
      expect(controller.owns('1'), true);
      expect(controller.owns('2'), false);
      expect(store.purchases, 1);
      expect(
        store.finished,
        isEmpty,
      ); // Full receipt synchronization still pending.
      final history = jsonDecode(store.cache!)['purchaseDiagnostics'] as List;
      expect(history.map((item) => item['code']), [
        'NETWORK_TIMEOUT',
        'CONFIRMED',
      ]);
      expect(
        history.every((item) => (item as Map).keys.toSet().length == 5),
        true,
      );
      api.confirmationFailure = null;
      await controller.refresh();
      expect(store.finished, ['credit-transaction']);
      expect(
        jsonDecode(store.cache!)['purchaseDiagnostics'].first['code'],
        'NETWORK_TIMEOUT',
      );
      expect(store.purchases, 1);
      controller.dispose();
    },
  );
  test(
    'readback cannot grant a wallpaper before server payment confirmation',
    () async {
      final api = CreditApi()
        ..confirmationFailure = const DeviceApiError(0, 'NETWORK_TIMEOUT');
      final store = CreditStore();
      final controller = IosAcquisitionController(api, store);
      await controller.initialize();
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(api.restoreCalls, 1);
      expect(controller.owns('1'), false);
      expect(store.finished, isEmpty);
      expect(controller.label('1'), '确认购买结果');
      expect(store.purchases, 1);
      controller.dispose();
    },
  );
  test(
    'invalid receipt is never recovered as a transient network failure',
    () async {
      final api = CreditApi()
        ..confirmationFailure = const DeviceApiError(
          403,
          'IOS_CREDIT_PURCHASE_INVALID',
        )
        ..fulfilledBeforeFailure = true;
      final store = CreditStore();
      final controller = IosAcquisitionController(api, store);
      await controller.initialize();
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(api.restoreCalls, 0);
      expect(controller.owns('1'), false);
      expect(store.finished, isEmpty);
      controller.dispose();
    },
  );
  test(
    'identical successful callbacks are ignored but refunds still synchronize',
    () async {
      final api = CreditApi();
      final store = CreditStore()..emitPaymentUpdate = true;
      final controller = IosAcquisitionController(api, store);
      await controller.initialize();
      expect(await controller.acquire('1'), true);
      await Future<void>.delayed(Duration.zero);
      expect(api.purchaseCalls, 1);
      expect(controller.busy, false);
      final refund = IosStoreTransaction(
        id: 'credit-transaction',
        productId: api.mappings['1']!,
        signedTransaction: 'new-refunded-proof',
        signedAppTransaction: 'signed-app',
        deviceVerificationId: installationId,
        environment: 'SANDBOX',
        productType: 'CONSUMABLE',
        quantity: 9,
        accountToken: accountToken,
        revoked: true,
      );
      store.listener?.call(refund);
      await Future<void>.delayed(Duration.zero);
      expect(api.purchaseCalls, 2);
      expect(controller.owns('1'), false);
      expect(controller.busy, false);
      controller.dispose();
    },
  );
  test(
    'confirmed rights do not hang on an unresponsive StoreKit finish',
    () async {
      final api = CreditApi(), store = UnresponsiveFinishStore();
      final controller = IosAcquisitionController(api, store);
      addTearDown(controller.dispose);
      await controller.initialize();
      final acquisition = controller.acquire('1');
      await Future<void>.delayed(Duration.zero);
      expect(controller.owns('1'), true);
      expect(await acquisition, true);
      expect(controller.busy, false);
      expect(store.purchases, 1);
      expect(
        jsonDecode(store.cache!)['purchaseDiagnostics'].last['phase'],
        'finish',
      );
    },
  );
  test(
    'pending approval survives restart and blocks repeated payment',
    () async {
      final api = CreditApi(),
          store = CreditStore()..next = const IosPurchaseResult('PENDING');
      var controller = IosAcquisitionController(api, store);
      await controller.initialize();
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      controller.dispose();
      controller = IosAcquisitionController(api, store);
      await controller.initialize();
      expect(controller.label('1'), '确认购买结果');
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(store.purchases, 1);
      controller.dispose();
    },
  );
  test(
    'server blocks duplicate checkout after local installation cache is lost',
    () async {
      final api = CreditApi()..paymentAllowed = false, store = CreditStore();
      final controller = IosAcquisitionController(api, store);
      await controller.initialize();
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(store.purchases, 0);
      expect(jsonDecode(store.cache!)['pendingCredit'], isNotNull);
      controller.dispose();
    },
  );
  test('cancellation grants nothing and CNY is required', () async {
    final api = CreditApi(),
        store = CreditStore()..next = const IosPurchaseResult('CANCELLED');
    final controller = IosAcquisitionController(api, store);
    await controller.initialize();
    expect(await controller.acquire('1'), false);
    expect(api.paid, isEmpty);
    expect(jsonDecode(store.cache!).containsKey('pendingCredit'), false);
    expect(await controller.acquire('1'), false);
    expect(store.purchases, 2);
    store.currencyCode = 'USD';
    await controller.refreshPrices();
    expect(controller.canAcquire('1'), false);
    expect(controller.label('1'), '请使用中国大陆商店');
    expect(controller.priceNote('1'), '1积分＝1元，下载积分仅支持中国大陆商店');
    await expectLater(
      controller.acquire('1'),
      throwsA(
        isA<IosAcquisitionNotice>().having(
          (notice) => notice.message,
          'message',
          '下载积分仅支持中国大陆商店，请切换后重试',
        ),
      ),
    );
    expect(store.purchases, 2);
    store.currencyCode = 'CNY';
    store.storefrontListener?.call();
    await controller.refreshPrices();
    expect(controller.canAcquire('1'), true);
    expect(controller.label('1'), '18个积分兑换壁纸');
    expect(controller.priceNote('1'), '1积分＝1元');
    controller.dispose();
  });
  test('first free respects eligibility and restore avoids payment', () async {
    final api = CreditApi()..allowance = IosFreeAllowance.available,
        store = CreditStore();
    final controller = IosAcquisitionController(api, store);
    await controller.initialize();
    expect(controller.label('1'), '首次免费获取');
    expect(controller.label('2'), '20个积分兑换壁纸');
    api.paid.add('2');
    await controller.restore();
    expect(controller.label('2'), '再次下载');
    expect(store.purchases, 0);
    controller.dispose();
  });
}
