import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/entitlements/ios_acquisition.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';

const productMap = {'1': 'test.wallpaper.1', '2': 'test.wallpaper.2'};
const accountToken = '43b10b19-f3ed-46fc-9a07-0757d72e5996';
const paidTransaction = IosStoreTransaction(
  id: '101',
  productId: 'test.wallpaper.2',
  signedTransaction: 'signed-test-transaction',
  environment: 'XCODE',
);

class TestAcquisitionApi implements IosAcquisitionApi {
  IosFreeAllowance allowance = IosFreeAllowance.available;
  final free = <String>{}, paid = <String>{};
  final claims = <(String, String)>[];
  int stateCalls = 0, purchaseCalls = 0;
  bool offline = false, rejectPurchase = false, claimTimeout = false;
  bool denyClaim = false, grantPaid = true;
  Completer<void>? holdClaim;
  IosAcquisitionState get snapshot => IosAcquisitionState(
    accountToken: accountToken,
    products: productMap,
    freeAllowance: allowance,
    freeWallpaperIds: Set.of(free),
    purchasedWallpaperIds: Set.of(paid),
  );
  @override
  Future<IosAcquisitionState> state() async {
    stateCalls++;
    if (offline) throw StateError('Network unavailable');
    return snapshot;
  }

  @override
  Future<IosAcquisitionState> claimFree(String id, String request) async {
    claims.add((id, request));
    if (holdClaim != null) await holdClaim!.future;
    if (claimTimeout) throw TimeoutException('Unknown result');
    if (denyClaim) throw const DeviceApiError(409, 'IOS_FREE_ALLOWANCE_USED');
    if (allowance != IosFreeAllowance.available && !free.contains(id)) {
      throw const DeviceApiError(409, 'IOS_FREE_ALLOWANCE_USED');
    }
    free.add(id);
    allowance = IosFreeAllowance.used;
    return snapshot;
  }

  @override
  Future<IosAcquisitionState> synchronize(
    IosStoreTransaction transaction,
  ) async {
    purchaseCalls++;
    if (rejectPurchase) {
      throw StateError('Server did not verify this transaction');
    }
    final id = productMap.entries
        .singleWhere((e) => e.value == transaction.productId)
        .key;
    if (transaction.revoked) {
      paid.remove(id);
    } else if (grantPaid) {
      paid.add(id);
    }
    return snapshot;
  }
}

class TestPurchaseStore implements IosPurchaseStore {
  String? cache;
  bool cacheFailure = false;
  final finished = <String>[];
  final synchronization = <bool>[];
  final unfinished = <IosStoreTransaction>[];
  int purchases = 0;
  IosPurchaseResult next = const IosPurchaseResult(
    'PURCHASED',
    paidTransaction,
  );
  void Function(IosStoreTransaction)? listener;
  @override
  Future<List<IosStoreProduct>> products(Set<String> ids) async => [
    for (final id in ids) IosStoreProduct(id, '¥1.00'),
  ];
  @override
  Future<IosPurchaseResult> purchase(String productId, String token) async {
    expect(token, accountToken);
    purchases++;
    if (next.transaction != null) unfinished.add(next.transaction!);
    return next;
  }

  @override
  Future<List<IosStoreTransaction>> transactions({bool restore = false}) async {
    synchronization.add(restore);
    return List.of(unfinished);
  }

  @override
  Future<void> finish(String id) async {
    finished.add(id);
    unfinished.removeWhere((value) => value.id == id);
  }

  @override
  Future<String?> readCache() async => cache;
  @override
  Future<void> writeCache(String value) async {
    if (cacheFailure) throw StateError('Cannot persist');
    cache = value;
  }

  @override
  Future<void> observe(void Function(IosStoreTransaction) onTransaction) async {
    listener = onTransaction;
  }

  @override
  Future<void> stopObserving() async {
    listener = null;
  }
}

void main() {
  late TestAcquisitionApi api;
  late TestPurchaseStore store;
  late IosAcquisitionController controller;
  setUp(() {
    api = TestAcquisitionApi();
    store = TestPurchaseStore();
    controller = IosAcquisitionController(api, store);
  });
  tearDown(() => controller.dispose());

  test(
    'initialization runs once; detail cache reads never sync Apple or query quota',
    () async {
      await Future.wait([controller.initialize(), controller.initialize()]);
      for (var i = 0; i < 20; i++) {
        expect(controller.label('1'), '首次免费获取');
        expect(controller.owns('1'), false);
      }
      expect(api.stateCalls, 1);
      expect(store.synchronization, [false]);
      expect(store.purchases, 0);
    },
  );

  test(
    'first claim chooses one wallpaper; retry download consumes nothing more',
    () async {
      await controller.initialize();
      expect(await controller.acquire('1'), true);
      expect(await controller.acquire('1'), true);
      expect(api.claims, hasLength(1));
      expect(api.free, {'1'});
      expect(controller.label('1'), '再次下载');
      expect(controller.label('2'), '¥1.00 购买并下载');
    },
  );

  test('concurrent taps cannot claim two different free wallpapers', () async {
    await controller.initialize();
    api.holdClaim = Completer<void>();
    final first = controller.acquire('1');
    await expectLater(
      controller.acquire('2'),
      throwsA(isA<IosAcquisitionNotice>()),
    );
    api.holdClaim!.complete();
    await first;
    expect(api.free, {'1'});
    expect(api.claims, hasLength(1));
  });

  test(
    'unknown free result survives restart and retries the original idempotency key',
    () async {
      await controller.initialize();
      api.claimTimeout = true;
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      final requestId = api.claims.single.$2;
      controller.dispose();
      controller = IosAcquisitionController(api, store);
      await controller.initialize();
      await expectLater(
        controller.acquire('2'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(api.claims, hasLength(1));
      api.claimTimeout = false;
      expect(await controller.acquire('1'), true);
      expect(api.claims.last, ('1', requestId));
    },
  );

  test(
    'free request is never dispatched when its durable pending write fails',
    () async {
      await controller.initialize();
      store.cacheFailure = true;
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(api.claims, isEmpty);
    },
  );

  test(
    'cached AVAILABLE cannot grant a free wallpaper when server is unavailable',
    () async {
      store.cache = jsonEncode({'state': api.snapshot.toJson()});
      api.offline = true;
      await controller.initialize();
      expect(controller.state!.freeAllowance, IosFreeAllowance.unknown);
      expect(await controller.acquire('1'), false);
      expect(api.claims, isEmpty);
      expect(store.purchases, 0);
    },
  );

  test(
    'cancellation and deferred approval never grant or finish a transaction',
    () async {
      api.allowance = IosFreeAllowance.used;
      await controller.initialize();
      store.next = const IosPurchaseResult('CANCELLED');
      expect(await controller.acquire('2'), false);
      store.next = const IosPurchaseResult('PENDING');
      await expectLater(
        controller.acquire('2'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(controller.owns('2'), false);
      expect(api.purchaseCalls, 0);
      expect(store.finished, isEmpty);
    },
  );

  test(
    'successful purchase is delivered before finishing, then downloaded from cache',
    () async {
      api.allowance = IosFreeAllowance.used;
      await controller.initialize();
      expect(await controller.acquire('2'), true);
      expect(api.purchaseCalls, 1);
      expect(store.finished, ['101']);
      expect(await controller.acquire('2'), true);
      expect(store.purchases, 1);
    },
  );

  test(
    'server failure leaves purchase unfinished; startup later recovers without charging again',
    () async {
      api.allowance = IosFreeAllowance.used;
      await controller.initialize();
      api.rejectPurchase = true;
      await expectLater(
        controller.acquire('2'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(store.finished, isEmpty);
      expect(controller.owns('2'), false);
      controller.dispose();
      controller = IosAcquisitionController(api, store);
      api.rejectPurchase = false;
      await controller.initialize();
      expect(controller.owns('2'), true);
      expect(store.finished, ['101']);
      expect(store.purchases, 1);
    },
  );

  test(
    'server response missing the bought entitlement cannot finish a purchase',
    () async {
      api.allowance = IosFreeAllowance.used;
      api.grantPaid = false;
      await controller.initialize();
      await expectLater(
        controller.acquire('2'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(controller.owns('2'), false);
      expect(store.finished, isEmpty);
    },
  );

  test(
    'unexpected StoreKit product cannot unlock the selected wallpaper',
    () async {
      api.allowance = IosFreeAllowance.used;
      await controller.initialize();
      store.next = const IosPurchaseResult(
        'PURCHASED',
        IosStoreTransaction(
          id: 'wrong',
          productId: 'test.wallpaper.1',
          signedTransaction: 'other',
          environment: 'XCODE',
        ),
      );
      await expectLater(
        controller.acquire('2'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      expect(api.purchaseCalls, 0);
      expect(store.finished, isEmpty);
    },
  );

  test(
    'Restore is explicit and maps valid transactions to the new installation',
    () async {
      api.allowance = IosFreeAllowance.used;
      await controller.initialize();
      expect(store.synchronization, [false]);
      store.unfinished.add(paidTransaction);
      expect(await controller.restore(), '已恢复购买，可以再次下载');
      expect(store.synchronization, [false, true]);
      expect(controller.owns('2'), true);
      expect(api.claims, isEmpty);
      expect(store.purchases, 0);
    },
  );

  test(
    'refund updates remove paid access without removing independently free ownership',
    () async {
      api.paid.add('2');
      api.free.add('2');
      await controller.initialize();
      store.listener!(
        const IosStoreTransaction(
          id: '101',
          productId: 'test.wallpaper.2',
          signedTransaction: 'signed-revocation',
          environment: 'XCODE',
          revoked: true,
        ),
      );
      await Future<void>.delayed(Duration.zero);
      expect(controller.state!.purchasedWallpaperIds, isEmpty);
      expect(controller.owns('2'), true);
    },
  );

  test(
    'definitive denial clears the original pending claim instead of trapping future purchases',
    () async {
      await controller.initialize();
      api.denyClaim = true;
      await expectLater(
        controller.acquire('1'),
        throwsA(isA<IosAcquisitionNotice>()),
      );
      api.allowance = IosFreeAllowance.used;
      api.denyClaim = false;
      await controller.refresh();
      expect(await controller.acquire('2'), true);
      expect(store.purchases, 1);
      expect(
        (jsonDecode(store.cache!) as Map).containsKey('pendingFree'),
        false,
      );
    },
  );
}
