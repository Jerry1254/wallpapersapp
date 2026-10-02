import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:qingjing_wallpaper/device/device_session.dart';
import 'package:qingjing_wallpaper/entitlements/ios_acquisition.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';

const productMap = {'1': 'test.wallpaper.1', '2': 'test.wallpaper.2'};
const installationId = 'ec9a0c33-f7b2-4293-90ee-c662d78165bc';
const accountToken = '43b10b19-f3ed-46fc-9a07-0757d72e5996';
const paidTransaction = IosStoreTransaction(
  id: '101',
  productId: 'test.wallpaper.2',
  signedTransaction: 'signed-test-transaction',
  signedAppTransaction: 'signed-test-app-transaction',
  deviceVerificationId: '4561cd09-07d7-4901-bf0b-ad6f7182079c',
  environment: 'XCODE',
);

class TestAcquisitionApi implements IosAcquisitionApi {
  IosFreeAllowance allowance = IosFreeAllowance.available;
  final free = <String>{}, paid = <String>{};
  final claims = <(String, String)>[];
  int generation = 0;
  IosPendingFreeReset? pendingReset;
  int stateCalls = 0, purchaseCalls = 0, resetCalls = 0;
  bool offline = false, rejectPurchase = false, claimTimeout = false;
  bool denyClaim = false, grantPaid = true;
  Completer<void>? holdClaim;
  IosAcquisitionState get snapshot => IosAcquisitionState(
    installationId: installationId,
    accountToken: accountToken,
    freeGeneration: generation,
    products: productMap,
    freeAllowance: pendingReset == null
        ? allowance
        : IosFreeAllowance.pendingReset,
    freeWallpaperIds: Set.of(free),
    purchasedWallpaperIds: Set.of(paid),
    pendingFreeReset: pendingReset,
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

  @override
  Future<IosAcquisitionState> completeFreeReset(
    IosPendingFreeReset reset,
  ) async {
    resetCalls++;
    if (pendingReset?.resetId != reset.resetId) {
      throw const DeviceApiError(404, 'IOS_FREE_RESET_NOT_FOUND');
    }
    free.clear();
    generation++;
    allowance = IosFreeAllowance.available;
    pendingReset = null;
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

class RecordingIosProof extends NativeIosDeviceProof {
  final assertedBodies = <String>[];
  @override
  Future<Map<String, dynamic>> key() async => {
    'keyId': 'app-attest-key',
    'registered': true,
  };
  @override
  Future<String> assertion(String keyId, String clientData) async {
    assertedBodies.add(clientData);
    return 'app-attest-assertion';
  }

  @override
  Future<String> deviceToken() async => 'device-check-token';
}

class RecordingIosSessions extends DeviceSessionManager {
  RecordingIosSessions()
    : super(HttpDeviceTransport(Uri.parse('https://unused.invalid/api/v1')));
  final requests =
      <({String path, String body, Map<String, String> headers})>[];
  int challenges = 0;
  @override
  Future<Map<String, dynamic>> authenticated(
    String path, {
    String method = 'GET',
    String? body,
    bool signed = false,
    Map<String, String> headers = const {},
    Set<int> accepted = const {},
  }) async {
    requests.add((path: path, body: body ?? '', headers: headers));
    if (path == '/device/ios/attestation/challenges') {
      challenges++;
      return {
        'challengeId': '2cc99fa7-a7d7-42d5-9d06-d2e6c9bcc43$challenges',
        'nonce': 'server-nonce-$challenges',
      };
    }
    return {
      'installationId': installationId,
      'accountToken': accountToken,
      'freeGeneration': path.contains('free-resets') ? 1 : 0,
      'freeAllowance': 'AVAILABLE',
      'freeWallpaperIds': <String>[],
      'purchasedWallpaperIds': <String>['2'],
      'products': [
        {'wallpaperId': '1', 'productId': 'test.wallpaper.1'},
        {'wallpaperId': '2', 'productId': 'test.wallpaper.2'},
      ],
      'pendingFreeReset': null,
      'checkedAt': '2026-10-02T09:00:00Z',
    };
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

  test('purchase and reset requests match the 2.14 proof contract', () async {
    final sessions = RecordingIosSessions();
    final proof = RecordingIosProof();
    final client = SessionIosAcquisitionApi(sessions, proof: proof);
    await client.synchronize(paidTransaction);
    final purchase = sessions.requests.singleWhere(
      (request) => request.path == '/device/ios/acquisition/purchases',
    );
    expect(jsonDecode(purchase.body), {
      'challengeId': '2cc99fa7-a7d7-42d5-9d06-d2e6c9bcc431',
      'nonce': 'server-nonce-1',
      'signedTransaction': 'signed-test-transaction',
      'signedAppTransaction': 'signed-test-app-transaction',
      'deviceVerificationId': '4561cd09-07d7-4901-bf0b-ad6f7182079c',
    });
    expect(purchase.headers['X-App-Attest-Key-Id'], 'app-attest-key');
    expect(proof.assertedBodies.single, purchase.body);

    final reset = IosPendingFreeReset(
      resetId: '530b034f-b849-43f9-bcf6-bd60b62397e4',
      expectedGeneration: 0,
      status: 'WAITING_DEVICE',
      expiresAt: DateTime.utc(2027),
    );
    await client.completeFreeReset(reset);
    final challenge = jsonDecode(sessions.requests[2].body) as Map;
    expect(challenge['action'], 'FREE_RESET');
    expect(challenge['resetId'], reset.resetId);
    final completion = sessions.requests.last;
    expect(
      completion.path,
      '/device/ios/acquisition/free-resets/${reset.resetId}/complete',
    );
    expect(jsonDecode(completion.body), {
      'challengeId': '2cc99fa7-a7d7-42d5-9d06-d2e6c9bcc432',
      'nonce': 'server-nonce-2',
      'deviceToken': 'device-check-token',
      'expectedGeneration': 0,
    });
    expect(proof.assertedBodies.last, completion.body);
  });

  test('server acquisition state parses the 2.14 product array', () {
    final state = IosAcquisitionState.fromJson({
      'installationId': installationId,
      'accountToken': accountToken,
      'freeGeneration': 4,
      'freeAllowance': 'AVAILABLE',
      'freeWallpaperIds': <String>[],
      'purchasedWallpaperIds': <String>['2'],
      'products': [
        {'wallpaperId': '1', 'productId': 'test.wallpaper.1'},
        {'wallpaperId': '2', 'productId': 'test.wallpaper.2'},
      ],
      'pendingFreeReset': null,
      'checkedAt': '2026-10-02T09:00:00Z',
    });
    expect(state.installationId, installationId);
    expect(state.freeGeneration, 4);
    expect(state.products, productMap);
    expect(state.purchasedWallpaperIds, {'2'});
  });

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

  test(
    'pending test reset is completed before acquisition becomes ready',
    () async {
      api
        ..free.add('1')
        ..allowance = IosFreeAllowance.used
        ..pendingReset = IosPendingFreeReset(
          resetId: '530b034f-b849-43f9-bcf6-bd60b62397e4',
          expectedGeneration: 0,
          status: 'WAITING_DEVICE',
          expiresAt: DateTime.utc(2027),
        );
      await controller.initialize();
      expect(api.resetCalls, 1);
      expect(controller.state!.freeGeneration, 1);
      expect(controller.state!.pendingFreeReset, isNull);
      expect(controller.state!.freeWallpaperIds, isEmpty);
      expect(controller.label('1'), '首次免费获取');
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
          signedAppTransaction: 'other-app-transaction',
          deviceVerificationId: '4561cd09-07d7-4901-bf0b-ad6f7182079c',
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
          signedAppTransaction: 'signed-app-revocation',
          deviceVerificationId: '4561cd09-07d7-4901-bf0b-ad6f7182079c',
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
