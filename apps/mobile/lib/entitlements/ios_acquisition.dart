import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';
import '../device/device_session.dart';

enum IosFreeAllowance { unknown, available, used, unavailable, pendingReset }

class IosPendingFreeReset {
  const IosPendingFreeReset({
    required this.resetId,
    required this.expectedGeneration,
    required this.status,
    required this.expiresAt,
  });

  factory IosPendingFreeReset.fromJson(Map<String, dynamic> value) {
    final resetId = value['resetId'] as String;
    final expectedGeneration = value['expectedGeneration'] as int;
    final status = value['status'] as String;
    final expiresAt = DateTime.parse(value['expiresAt'] as String).toUtc();
    if (!_uuid.hasMatch(resetId) ||
        expectedGeneration < 0 ||
        status.isEmpty ||
        !expiresAt.isAfter(
          DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
        )) {
      throw const FormatException('Invalid pending iOS free reset');
    }
    return IosPendingFreeReset(
      resetId: resetId,
      expectedGeneration: expectedGeneration,
      status: status,
      expiresAt: expiresAt,
    );
  }

  final String resetId, status;
  final int expectedGeneration;
  final DateTime expiresAt;

  Map<String, dynamic> toJson() => {
    'resetId': resetId,
    'expectedGeneration': expectedGeneration,
    'status': status,
    'expiresAt': expiresAt.toIso8601String(),
  };
}

final _uuid = RegExp(
  r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
);

class IosCreditOffer {
  const IosCreditOffer(
    this.credits,
    this.packCredits,
    this.quantity,
    this.ready,
  );
  factory IosCreditOffer.fromJson(Map<String, dynamic> value) {
    final credits = value['credits'] as int?;
    final pack = value['packCredits'] as int?;
    final quantity = value['purchaseQuantity'] as int?;
    if (credits != null &&
        (pack == null ||
            quantity == null ||
            pack < 1 ||
            pack > 3 ||
            quantity < 1 ||
            quantity > 10 ||
            credits != pack * quantity)) {
      throw const FormatException('Invalid credit offer');
    }
    return IosCreditOffer(
      credits,
      pack,
      quantity,
      value['priceSyncStatus'] == 'READY' &&
          value['priceSource'] == 'APP_STORE_CONNECT' &&
          value['priceCurrency'] == 'CNY',
    );
  }
  final int? credits, packCredits, quantity;
  final bool ready;
  Map<String, dynamic> toJson() => {
    'acquisitionMode': 'CREDITS',
    'credits': credits,
    'packCredits': packCredits,
    'purchaseQuantity': quantity,
    'priceSyncStatus': ready ? 'READY' : 'UNAVAILABLE',
    'priceSource': 'APP_STORE_CONNECT',
    'priceCurrency': 'CNY',
  };
}

class IosCreditOrder {
  IosCreditOrder(this.value) {
    if (!_uuid.hasMatch(id) ||
        !_uuid.hasMatch(token) ||
        !RegExp(r'^[1-9][0-9]*$').hasMatch(wallpaperId) ||
        quantity < 1 ||
        quantity > 10 ||
        packCredits < 1 ||
        packCredits > 3 ||
        credits != quantity * packCredits ||
        value['amount'] != '$credits.00' ||
        !{'OPEN', 'FULFILLED'}.contains(value['status'])) {
      throw const FormatException('Invalid credit order');
    }
  }
  final Map<String, dynamic> value;
  String get id => value['orderId'] as String;
  String get wallpaperId => value['wallpaperId'] as String;
  String get productId => value['productId'] as String;
  String get token => value['accountToken'] as String;
  int get credits => value['credits'] as int;
  int get packCredits => value['packCredits'] as int;
  int get quantity => value['quantity'] as int;
  bool get paymentAllowed => value['paymentAllowed'] == true;
  bool get fulfilled => value['status'] == 'FULFILLED';
}

class IosProductCatalogue {
  const IosProductCatalogue(
    this.productIds, {
    this.chinaReferencePrices = const {},
    this.creditOffers = const {},
    this.freeEligible = const {},
  });
  factory IosProductCatalogue.fromItems(dynamic value) {
    final products = <String, String>{};
    final prices = <String, String>{};
    final offers = <String, IosCreditOffer>{};
    final free = <String, bool>{};
    final legacyIds = <String>{};
    if (value is List) {
      for (final item in value) {
        final product = Map<String, dynamic>.from(item as Map);
        final wallpaperId = product['wallpaperId'] as String;
        final productId = product['productId'] as String;
        final isCredit = product['acquisitionMode'] == 'CREDITS';
        if (products.containsKey(wallpaperId) ||
            (!isCredit && !legacyIds.add(productId))) {
          throw const FormatException('Duplicate wallpaper product mapping');
        }
        if (!isCredit && productId.isEmpty) {
          throw const FormatException('Empty product ID');
        }
        products[wallpaperId] = productId;
        free[wallpaperId] = product['firstFreeEligible'] != false;
        if (isCredit) {
          offers[wallpaperId] = IosCreditOffer.fromJson(product);
          if (offers[wallpaperId]!.credits != null && productId.isEmpty) {
            throw const FormatException('Empty credit product ID');
          }
        } else {
          final price = product['chinaReferencePrice'];
          if (price != null) {
            if (price is! String ||
                !RegExp(r'^(?:0|[1-9][0-9]{0,7})\.[0-9]{2}$').hasMatch(price) ||
                price == '0.00') {
              throw const FormatException('Invalid China reference price');
            }
            if (product['priceSource'] == 'APP_STORE_CONNECT' &&
                product['priceCurrency'] == 'CNY' &&
                product['priceSyncStatus'] == 'READY') {
              prices[productId] = price;
            }
          }
        }
      }
    } else if (value is Map) {
      products.addAll(Map<String, String>.from(value));
      if (products.values.any((id) => id.isEmpty) ||
          products.values.toSet().length != products.length) {
        throw const FormatException('Invalid legacy mapping');
      }
    } else {
      throw const FormatException('Invalid wallpaper product mapping');
    }
    if (products.keys.any((id) => !RegExp(r'^[1-9][0-9]*$').hasMatch(id))) {
      throw const FormatException('Invalid wallpaper ID');
    }
    return IosProductCatalogue(
      products,
      chinaReferencePrices: prices,
      creditOffers: offers,
      freeEligible: free,
    );
  }
  final Map<String, String> productIds, chinaReferencePrices;
  final Map<String, IosCreditOffer> creditOffers;
  final Map<String, bool> freeEligible;
  List<Map<String, dynamic>> toJson() => [
    for (final entry in productIds.entries)
      {
        'wallpaperId': entry.key,
        'productId': entry.value,
        'firstFreeEligible': freeEligible[entry.key] ?? true,
        if (creditOffers[entry.key] case final offer?) ...offer.toJson(),
        if (chinaReferencePrices[entry.value] case final price?) ...{
          'chinaReferencePrice': price,
          'priceSource': 'APP_STORE_CONNECT',
          'priceCurrency': 'CNY',
          'priceSyncStatus': 'READY',
        },
      },
  ];
}

class IosAcquisitionState {
  const IosAcquisitionState({
    required this.installationId,
    required this.accountToken,
    required this.freeGeneration,
    required this.products,
    this.chinaReferencePrices = const {},
    this.creditOffers = const {},
    this.freeEligible = const {},
    this.freeAllowance = IosFreeAllowance.unknown,
    this.freeWallpaperIds = const {},
    this.purchasedWallpaperIds = const {},
    this.pendingFreeReset,
    this.checkedAt,
  });
  factory IosAcquisitionState.fromJson(Map<String, dynamic> value) {
    final installationId = value['installationId'] as String;
    final token = value['accountToken'] as String;
    final generation = value['freeGeneration'] as int;
    if (!_uuid.hasMatch(installationId) ||
        !_uuid.hasMatch(token) ||
        generation < 0) {
      throw const FormatException('Invalid iOS acquisition identity');
    }
    final catalogue = IosProductCatalogue.fromItems(value['products']);
    final products = catalogue.productIds;
    final free = Set<String>.from(value['freeWallpaperIds'] as List);
    final paid = Set<String>.from(value['purchasedWallpaperIds'] as List);
    if ([
      ...products.keys,
      ...free,
      ...paid,
    ].any((id) => !RegExp(r'^[1-9][0-9]*$').hasMatch(id))) {
      throw const FormatException('Invalid wallpaper product mapping');
    }
    final allowance = switch (value['freeAllowance']) {
      'AVAILABLE' => IosFreeAllowance.available,
      'USED' => IosFreeAllowance.used,
      'UNAVAILABLE' => IosFreeAllowance.unavailable,
      'PENDING_RESET' => IosFreeAllowance.pendingReset,
      'UNKNOWN' => IosFreeAllowance.unknown,
      _ => throw const FormatException('Unknown free allowance'),
    };
    final pendingValue = value['pendingFreeReset'];
    final pending = pendingValue == null
        ? null
        : IosPendingFreeReset.fromJson(
            Map<String, dynamic>.from(pendingValue as Map),
          );
    if ((allowance == IosFreeAllowance.pendingReset) != (pending != null)) {
      throw const FormatException('Inconsistent iOS free reset state');
    }
    return IosAcquisitionState(
      installationId: installationId,
      accountToken: token,
      freeGeneration: generation,
      products: products,
      chinaReferencePrices: catalogue.chinaReferencePrices,
      creditOffers: catalogue.creditOffers,
      freeEligible: catalogue.freeEligible,
      freeAllowance: allowance,
      freeWallpaperIds: free,
      purchasedWallpaperIds: paid,
      pendingFreeReset: pending,
      checkedAt: value['checkedAt'] == null
          ? null
          : DateTime.parse(value['checkedAt'] as String).toUtc(),
    );
  }
  final String installationId, accountToken;
  final int freeGeneration;
  final Map<String, String> products;
  final Map<String, String> chinaReferencePrices;
  final Map<String, IosCreditOffer> creditOffers;
  final Map<String, bool> freeEligible;
  IosProductCatalogue get catalogue => IosProductCatalogue(
    products,
    chinaReferencePrices: chinaReferencePrices,
    creditOffers: creditOffers,
    freeEligible: freeEligible,
  );
  final IosFreeAllowance freeAllowance;
  final Set<String> freeWallpaperIds, purchasedWallpaperIds;
  final IosPendingFreeReset? pendingFreeReset;
  final DateTime? checkedAt;
  bool owns(String id) =>
      freeWallpaperIds.contains(id) || purchasedWallpaperIds.contains(id);
  Map<String, dynamic> toJson({bool cache = false}) => {
    'installationId': installationId,
    'accountToken': accountToken,
    'freeGeneration': freeGeneration,
    'products': catalogue.toJson(),
    // A cached AVAILABLE response never makes a new device eligible offline.
    'freeAllowance': cache && freeAllowance == IosFreeAllowance.available
        ? 'UNKNOWN'
        : switch (freeAllowance) {
            IosFreeAllowance.pendingReset => 'PENDING_RESET',
            _ => freeAllowance.name.toUpperCase(),
          },
    'freeWallpaperIds': freeWallpaperIds.toList(),
    'purchasedWallpaperIds': purchasedWallpaperIds.toList(),
    'pendingFreeReset': pendingFreeReset?.toJson(),
    'checkedAt': checkedAt?.toIso8601String(),
  };
}

class IosAcquisitionNotice implements Exception {
  const IosAcquisitionNotice(this.message);
  final String message;
}

abstract interface class IosAcquisitionApi {
  Future<IosAcquisitionState> state();
  Future<IosProductCatalogue> productCatalogue();
  Future<IosAcquisitionState> claimFree(String wallpaperId, String requestId);
  Future<IosAcquisitionState> synchronize(IosStoreTransaction transaction);
  Future<IosAcquisitionState> completeFreeReset(IosPendingFreeReset reset);
}

abstract interface class IosCreditAcquisitionApi {
  Future<IosCreditOrder> creditOrder(
    String wallpaperId,
    Map<String, dynamic> identity,
  );
  Future<IosAcquisitionState> restoreCredits(Map<String, dynamic> identity);
  Future<IosAcquisitionState> cancelCreditOrder(
    String orderId,
    Map<String, dynamic> identity,
  );
}

/// Acquisition adapter. Keep its build flag disabled until the
/// matching server configuration and database migrations are deployed.
class SessionIosAcquisitionApi
    implements IosAcquisitionApi, IosCreditAcquisitionApi {
  SessionIosAcquisitionApi(
    this.sessions, {
    this.proof = const NativeIosDeviceProof(),
  });
  final DeviceSessionManager sessions;
  final NativeIosDeviceProof proof;
  @override
  Future<IosProductCatalogue> productCatalogue() async {
    final value = await sessions.authenticated('/device/ios/products');
    return IosProductCatalogue.fromItems(value['items']);
  }

  Future<String>? _key;
  Future<String> _keyId() async {
    try {
      return await (_key ??= _enroll());
    } catch (_) {
      _key = null;
      rethrow;
    }
  }

  Future<String> _enroll() async {
    final value = await proof.key();
    final key = value['keyId'] as String;
    if (value['registered'] != true) {
      final challenge = await sessions.authenticated(
        '/device/ios/attestation/challenges',
        method: 'POST',
        signed: true,
        body: jsonEncode({'keyId': key, 'action': 'ENROLL'}),
      );
      final attestation = await proof.attest(
        key,
        challenge['clientData'] as String,
      );
      final registration = await sessions.authenticated(
        '/device/ios/attestation/registrations',
        method: 'POST',
        signed: true,
        body: jsonEncode({
          'keyId': key,
          'challengeId': challenge['challengeId'],
          'attestation': attestation,
        }),
      );
      if (registration['registered'] != true) {
        throw const DeviceApiError(
          0,
          'IOS_ATTESTATION_REGISTRATION_UNCONFIRMED',
        );
      }
      await proof.remember(key);
    }
    return key;
  }

  Future<IosAcquisitionState> _withProof(
    String action,
    String path, {
    String? wallpaperId,
    String? requestId,
    String? resetId,
    int? expectedGeneration,
  }) async {
    for (
      var enrollmentAttempt = 0;
      enrollmentAttempt < 2;
      enrollmentAttempt++
    ) {
      try {
        return await _withFreshChallenge(
          action,
          path,
          wallpaperId: wallpaperId,
          requestId: requestId,
          resetId: resetId,
          expectedGeneration: expectedGeneration,
        );
      } on DeviceApiError catch (failure) {
        if (enrollmentAttempt == 0 &&
            failure.code == 'IOS_ATTESTATION_INVALID') {
          await proof.resetKey();
          _key = null;
          continue;
        }
        rethrow;
      }
    }
    throw const DeviceApiError(0, 'IOS_DEVICE_PROOF_UNAVAILABLE');
  }

  Future<IosAcquisitionState> _withFreshChallenge(
    String action,
    String path, {
    String? wallpaperId,
    String? requestId,
    String? resetId,
    int? expectedGeneration,
  }) async {
    final key = await _keyId();
    for (var challengeAttempt = 0; challengeAttempt < 2; challengeAttempt++) {
      final challenge = await sessions.authenticated(
        '/device/ios/attestation/challenges',
        method: 'POST',
        signed: true,
        body: jsonEncode({
          'keyId': key,
          'action': action,
          'wallpaperId': ?wallpaperId,
          'resetId': ?resetId,
        }),
      );
      final body = jsonEncode({
        'challengeId': challenge['challengeId'],
        'nonce': challenge['nonce'],
        'deviceToken': await proof.deviceToken(),
        'wallpaperId': ?wallpaperId,
        'requestId': ?requestId,
        'expectedGeneration': ?expectedGeneration,
      });
      final assertion = await proof.assertion(key, body);
      try {
        return IosAcquisitionState.fromJson(
          await sessions.authenticated(
            path,
            method: 'POST',
            body: body,
            signed: true,
            headers: {
              'X-App-Attest-Key-Id': key,
              'X-App-Attest-Assertion': assertion,
            },
          ),
        );
      } on DeviceApiError catch (failure) {
        if (challengeAttempt == 0 &&
            const {
              'IOS_ASSERTION_REPLAY',
              'IOS_CHALLENGE_EXPIRED',
            }.contains(failure.code)) {
          continue;
        }
        rethrow;
      }
    }
    throw const DeviceApiError(0, 'IOS_DEVICE_PROOF_UNAVAILABLE');
  }

  @override
  Future<IosAcquisitionState> state() =>
      _withProof('STATUS', '/device/ios/acquisition/status');
  @override
  Future<IosAcquisitionState> claimFree(String wallpaperId, String requestId) =>
      _withProof(
        'FREE_CLAIM',
        '/device/ios/acquisition/free-claims',
        wallpaperId: wallpaperId,
        requestId: requestId,
      );
  @override
  Future<IosAcquisitionState> synchronize(
    IosStoreTransaction transaction,
  ) async {
    for (
      var enrollmentAttempt = 0;
      enrollmentAttempt < 2;
      enrollmentAttempt++
    ) {
      final key = await _keyId();
      try {
        for (
          var challengeAttempt = 0;
          challengeAttempt < 2;
          challengeAttempt++
        ) {
          final challenge = await sessions.authenticated(
            '/device/ios/attestation/challenges',
            method: 'POST',
            signed: true,
            body: jsonEncode({'keyId': key, 'action': 'PURCHASE_SYNC'}),
          );
          final body = jsonEncode({
            'challengeId': challenge['challengeId'],
            'nonce': challenge['nonce'],
            'signedTransaction': transaction.signedTransaction,
            'signedAppTransaction': transaction.signedAppTransaction,
            'deviceVerificationId': transaction.deviceVerificationId,
          });
          final assertion = await proof.assertion(key, body);
          try {
            return IosAcquisitionState.fromJson(
              await sessions.authenticated(
                '/device/ios/acquisition/purchases',
                method: 'POST',
                signed: true,
                body: body,
                headers: {
                  'X-App-Attest-Key-Id': key,
                  'X-App-Attest-Assertion': assertion,
                },
              ),
            );
          } on DeviceApiError catch (failure) {
            if (challengeAttempt == 0 &&
                const {
                  'IOS_ASSERTION_REPLAY',
                  'IOS_CHALLENGE_EXPIRED',
                }.contains(failure.code)) {
              continue;
            }
            rethrow;
          }
        }
      } on DeviceApiError catch (failure) {
        if (enrollmentAttempt == 0 &&
            failure.code == 'IOS_ATTESTATION_INVALID') {
          await proof.resetKey();
          _key = null;
          continue;
        }
        rethrow;
      }
    }
    throw const DeviceApiError(0, 'IOS_DEVICE_PROOF_UNAVAILABLE');
  }

  Future<Map<String, dynamic>> _creditProof(
    String action,
    String path,
    Map<String, dynamic> identity, {
    String? wallpaperId,
  }) async {
    for (var enrollment = 0; enrollment < 2; enrollment++) {
      try {
        final key = await _keyId();
        for (var attempt = 0; attempt < 2; attempt++) {
          final challenge = await sessions.authenticated(
            '/device/ios/attestation/challenges',
            method: 'POST',
            signed: true,
            body: jsonEncode({
              'keyId': key,
              'action': action,
              'wallpaperId': ?wallpaperId,
            }),
          );
          final body = jsonEncode({
            'challengeId': challenge['challengeId'],
            'nonce': challenge['nonce'],
            'wallpaperId': ?wallpaperId,
            'signedAppTransaction': identity['signedAppTransaction'],
            'deviceVerificationId': identity['deviceVerificationId'],
          });
          final assertion = await proof.assertion(key, body);
          try {
            return await sessions.authenticated(
              path,
              method: 'POST',
              signed: true,
              body: body,
              headers: {
                'X-App-Attest-Key-Id': key,
                'X-App-Attest-Assertion': assertion,
              },
            );
          } on DeviceApiError catch (failure) {
            if (attempt == 0 &&
                {
                  'IOS_ASSERTION_REPLAY',
                  'IOS_CHALLENGE_EXPIRED',
                }.contains(failure.code)) {
              continue;
            }
            rethrow;
          }
        }
      } on DeviceApiError catch (failure) {
        if (enrollment == 0 && failure.code == 'IOS_ATTESTATION_INVALID') {
          await proof.resetKey();
          _key = null;
          continue;
        }
        rethrow;
      }
    }
    throw const DeviceApiError(0, 'IOS_DEVICE_PROOF_UNAVAILABLE');
  }

  @override
  Future<IosCreditOrder> creditOrder(
    String wallpaperId,
    Map<String, dynamic> identity,
  ) async => IosCreditOrder(
    await _creditProof(
      'CREDIT_ORDER',
      '/device/ios/acquisition/credit-orders',
      identity,
      wallpaperId: wallpaperId,
    ),
  );
  @override
  Future<IosAcquisitionState> restoreCredits(
    Map<String, dynamic> identity,
  ) async => IosAcquisitionState.fromJson(
    await _creditProof(
      'CREDIT_RESTORE',
      '/device/ios/acquisition/credit-restores',
      identity,
    ),
  );

  @override
  Future<IosAcquisitionState> cancelCreditOrder(
    String orderId,
    Map<String, dynamic> identity,
  ) async => IosAcquisitionState.fromJson(
    await _creditProof(
      'CREDIT_CANCEL',
      '/device/ios/acquisition/credit-orders/$orderId/cancel',
      identity,
    ),
  );

  @override
  Future<IosAcquisitionState> completeFreeReset(IosPendingFreeReset reset) =>
      _withProof(
        'FREE_RESET',
        '/device/ios/acquisition/free-resets/${reset.resetId}/complete',
        resetId: reset.resetId,
        expectedGeneration: reset.expectedGeneration,
      );
}

/// One coordinator per running installation. Details read this cache without
/// prompting Apple or checking the free quota on every navigation.
class IosAcquisitionController extends ChangeNotifier {
  IosAcquisitionController(this.api, this.store);
  final IosAcquisitionApi api;
  final IosPurchaseStore store;
  IosProductCatalogue? _catalogue;
  IosAcquisitionState? state;
  Map<String, IosStoreProduct> products = {};
  bool busy = false, ready = false;
  bool unavailable = false;
  bool pricesLoading = false, _priceRefreshPending = false;
  bool _reloadCatalogueRequested = false;
  String? error;
  String? _pendingFreeId, _pendingRequestId;
  int? _pendingFreeGeneration;
  IosCreditOrder? _pendingCredit;
  bool _pendingCreditCancelled = false;
  Future<void>? _initialization;
  Future<void>? _productRefresh;
  final List<IosStoreTransaction> _updates = [];
  bool _closed = false;
  bool owns(String id) => state?.owns(id) == true;

  String? _productId(String wallpaperId) {
    final value = (_catalogue?.productIds ?? state?.products)?[wallpaperId];
    return value == null || value.isEmpty ? null : value;
  }

  IosProductCatalogue get _offers => _catalogue ?? state!.catalogue;
  bool _canFree(String id) =>
      state?.freeAllowance == IosFreeAllowance.available &&
      _offers.productIds.containsKey(id) &&
      (_offers.freeEligible[id] ?? true);
  bool _creditAvailable(String id) {
    final offer = _offers.creditOffers[id];
    final product = products[_productId(id)];
    return offer?.ready == true &&
        offer?.credits != null &&
        product?.productType == 'CONSUMABLE' &&
        product?.currencyCode == 'CNY' &&
        num.tryParse(product?.price ?? '') == offer?.packCredits;
  }

  bool _creditCurrencyMismatch(String id) {
    final product = products[_productId(id)];
    return _offers.creditOffers.containsKey(id) &&
        product != null &&
        product.currencyCode != 'CNY';
  }

  Future<void> initialize() => _initialization ??= _initialize();
  Future<void> _initialize() async {
    busy = true;
    _notify();
    try {
      final cache = await store.readCache();
      if (cache != null) {
        final value = jsonDecode(cache) as Map<String, dynamic>;
        state = IosAcquisitionState.fromJson(
          value['state'] as Map<String, dynamic>,
        );
        // Ignore any saved free availability, even if an old cache contains it.
        state = IosAcquisitionState.fromJson(state!.toJson(cache: true));
        final pending = value['pendingFree'] as Map<String, dynamic>?;
        _pendingFreeId = pending?['wallpaperId'] as String?;
        _pendingRequestId = pending?['requestId'] as String?;
        _pendingFreeGeneration = pending?['freeGeneration'] as int?;
        _pendingCreditCancelled = value['pendingCreditCancelled'] == true;
        if (value['pendingCredit'] is Map) {
          _pendingCredit = IosCreditOrder(
            Map<String, dynamic>.from(value['pendingCredit'] as Map),
          );
        }
        _notify();
      }
    } catch (_) {
      /* A damaged cache cannot grant delivery on the server. */
    }
    try {
      await store.observe((transaction) {
        _updates.removeWhere((value) => value.id == transaction.id);
        _updates.add(transaction);
        _drainUpdates();
      }, onStorefrontChanged: () => unawaited(refreshPrices()));
    } catch (_) {
      error = '无法监听购买结果，请稍后重试';
    }
    busy = false;
    await refresh();
  }

  Future<void> refresh() async {
    if (busy) return;
    busy = true;
    error = null;
    unavailable = false;
    _notify();
    try {
      var fresh = await api.state();
      await _accept(fresh);
      if (fresh.pendingFreeReset != null) {
        fresh = await api.completeFreeReset(fresh.pendingFreeReset!);
        await _accept(fresh);
      }
      await refreshPrices(reloadCatalogue: false);
      ready = true;
      for (final transaction in await store.transactions()) {
        await _deliver(transaction);
      }
    } on DeviceApiError catch (failure) {
      _applyApiFailure(failure);
    } on IosAcquisitionNotice catch (notice) {
      error = notice.message;
    } catch (_) {
      error = '获取资格暂时无法确认，请重试';
    } finally {
      busy = false;
      _notify();
      _drainUpdates();
    }
  }

  /// Refresh backend display prices and StoreKit availability without querying
  /// free quota or invoking Restore (which can require authentication).
  Future<void> refreshPrices({bool reloadCatalogue = true}) {
    if (_closed || state == null) return Future.value();
    _priceRefreshPending = true;
    _reloadCatalogueRequested |= reloadCatalogue;
    return _productRefresh ??= _refreshPrices().whenComplete(() {
      _productRefresh = null;
    });
  }

  Future<void> _refreshPrices() async {
    pricesLoading = true;
    products = {};
    _notify();
    try {
      do {
        _priceRefreshPending = false;
        final reloadCatalogue = _reloadCatalogueRequested;
        _reloadCatalogueRequested = false;
        var catalogue = _catalogue ?? state!.catalogue;
        var found = <IosStoreProduct>[];
        try {
          if (reloadCatalogue) {
            try {
              catalogue = await api.productCatalogue();
            } on DeviceApiError catch (failure) {
              if (failure.status != 404) rethrow;
              // Until the server update is deployed, only use server status
              // data. Never replace a missing reference price with app constants.
              catalogue = state!.catalogue;
            }
          }
          found = await store.products(
            catalogue.productIds.values.where((id) => id.isNotEmpty).toSet(),
          );
        } catch (_) {
          // A failed price query must not leave a stale price on screen.
        }
        if (_closed) return;
        if (!_priceRefreshPending) {
          _catalogue = catalogue;
          products = {for (final product in found) product.id: product};
        }
      } while (_priceRefreshPending);
    } finally {
      pricesLoading = false;
      _notify();
    }
  }

  String label(String wallpaperId) {
    if (owns(wallpaperId)) return '再次下载';
    if (unavailable) return 'Apple 验证暂不可用';
    if (busy) return '正在确认资格';
    if (state?.freeAllowance == IosFreeAllowance.pendingReset) {
      return '测试资格重置处理中';
    }
    if (!ready || error != null) return '重试获取资格';
    if (state!.freeAllowance == IosFreeAllowance.unknown) return '重试获取资格';
    if (_canFree(wallpaperId)) return '首次免费获取';
    if (_pendingCredit?.wallpaperId == wallpaperId) return '确认购买结果';
    final credit = _offers.creditOffers[wallpaperId];
    if (credit != null) {
      if (credit.credits == null) return '商品尚未配置';
      if (pricesLoading) return '正在获取价格';
      if (_creditCurrencyMismatch(wallpaperId)) return '请使用中国大陆商店';
      return _creditAvailable(wallpaperId)
          ? '${credit.credits}个积分兑换壁纸'
          : 'App Store 暂不可购买';
    }
    final productId = _productId(wallpaperId);
    if (productId == null) return '商品尚未配置';
    if (pricesLoading) return '正在获取价格';
    final product = products[productId];
    if (product == null) return 'App Store 暂不可购买';
    final price =
        (_catalogue ?? state!.catalogue).chinaReferencePrices[productId];
    return price == null ? '购买并下载' : '¥$price 购买并下载';
  }

  String? priceNote(String wallpaperId) {
    if (state != null &&
        _offers.creditOffers.containsKey(wallpaperId) &&
        !owns(wallpaperId)) {
      if (!pricesLoading &&
          !_canFree(wallpaperId) &&
          _creditCurrencyMismatch(wallpaperId)) {
        return '1积分＝1元，下载积分仅支持中国大陆商店';
      }
      return '1积分＝1元';
    }
    if (owns(wallpaperId) ||
        busy ||
        pricesLoading ||
        !ready ||
        error != null ||
        state?.freeAllowance != IosFreeAllowance.used) {
      return null;
    }
    final productId = _productId(wallpaperId);
    final product = products[productId];
    if (product == null) return null;
    return (_catalogue ?? state!.catalogue).chinaReferencePrices.containsKey(
          productId,
        )
        ? '中国区参考价，实际付款以 Apple 确认页为准'
        : '实际价格和付款币种以 Apple 确认页为准';
  }

  bool canAcquire(String id) {
    if (busy ||
        unavailable ||
        state?.freeAllowance == IosFreeAllowance.pendingReset) {
      return false;
    }
    if (owns(id)) return true;
    if (!ready ||
        error != null ||
        state!.freeAllowance == IosFreeAllowance.unknown) {
      return true;
    }
    if (_canFree(id) || _pendingCredit?.wallpaperId == id) return true;
    if (_offers.creditOffers.containsKey(id)) {
      return !pricesLoading && _creditAvailable(id);
    }
    final productId = _productId(id);
    if (productId == null) return false;
    return (!pricesLoading && products.containsKey(productId));
  }

  /// False indicates cancellation, deferred approval, or an eligibility refresh.
  /// Only a server-confirmed entitlement permits the caller to request delivery.
  Future<bool> acquire(String wallpaperId) async {
    if (busy) throw const IosAcquisitionNotice('正在处理，请稍候');
    if (unavailable) throw const IosAcquisitionNotice('Apple 验证暂不可用，请稍后重试');
    if (owns(wallpaperId)) return true;
    if (!ready ||
        error != null ||
        state!.freeAllowance == IosFreeAllowance.unknown) {
      await refresh();
      return false;
    }
    if (state!.freeAllowance == IosFreeAllowance.pendingReset) {
      throw const IosAcquisitionNotice('测试资格重置处理中，请稍后重试');
    }
    final productId = _productId(wallpaperId);
    if (productId == null && !_canFree(wallpaperId)) {
      throw const IosAcquisitionNotice('此壁纸尚未配置 Apple 内购，请稍后重试');
    }
    busy = true;
    error = null;
    unavailable = false;
    _notify();
    try {
      if (_canFree(wallpaperId) || _pendingFreeId != null) {
        if (_pendingFreeId != null && _pendingFreeId != wallpaperId) {
          throw const IosAcquisitionNotice('上次免费获取尚未确认，请回到原壁纸重试');
        }
        _pendingFreeId = wallpaperId;
        _pendingRequestId ??= requestUuid();
        _pendingFreeGeneration ??= state!.freeGeneration;
        await _cache(); // Durable before dispatch; retry the same acquisition.
        await _accept(await api.claimFree(wallpaperId, _pendingRequestId!));
        if (!owns(wallpaperId)) {
          throw const IosAcquisitionNotice('免费获取结果尚未确认，请重试');
        }
      } else if (_offers.creditOffers.containsKey(wallpaperId)) {
        if (!await _buyCredits(wallpaperId)) return false;
      } else {
        if (!products.containsKey(productId)) {
          throw const IosAcquisitionNotice('此壁纸暂不可购买，请稍后重试');
        }
        final result = await store.purchase(productId!, state!.accountToken);
        if (result.status == 'CANCELLED') return false;
        if (result.status == 'PENDING') {
          throw const IosAcquisitionNotice('购买正在等待批准，完成后会自动更新权益');
        }
        final transaction = result.transaction;
        if (result.status != 'PURCHASED' ||
            transaction == null ||
            transaction.productId != productId ||
            transaction.revoked) {
          throw const IosAcquisitionNotice('购买结果未通过验证，请尝试恢复购买');
        }
        await _deliver(transaction);
        if (!owns(wallpaperId)) {
          throw const IosAcquisitionNotice('购买已完成，权益尚未确认，请恢复购买；无需再次付款');
        }
      }
      return true;
    } on DeviceApiError catch (failure) {
      if (const {
            'IOS_FREE_ALLOWANCE_USED',
            'IOS_FREE_CLAIM_RESET',
            'IOS_FREE_GENERATION_CONFLICT',
          }.contains(failure.code) &&
          _pendingFreeId != null) {
        _pendingFreeId = null;
        _pendingRequestId = null;
        _pendingFreeGeneration = null;
        await _cache();
        error = failure.code == 'IOS_FREE_ALLOWANCE_USED'
            ? '免费资格已使用，请重新确认获取方式'
            : '免费资格已经重置，请重新确认获取方式';
        throw IosAcquisitionNotice(error!);
      }
      if (failure.code == 'IOS_FREE_RESET_PENDING') {
        error = '测试资格重置处理中，请稍后重试';
        throw IosAcquisitionNotice(error!);
      }
      if (const {
        'IOS_ACQUISITION_UNAVAILABLE',
        'IOS_DEVICE_PROOF_UNAVAILABLE',
      }.contains(failure.code)) {
        unavailable = true;
        error = 'Apple 验证暂不可用，请稍后重试';
        throw IosAcquisitionNotice(error!);
      }
      throw const IosAcquisitionNotice('获取结果尚未确认，请重试或恢复购买；无需重复付款');
    } on IosAcquisitionNotice {
      rethrow;
    } catch (_) {
      throw const IosAcquisitionNotice('获取结果尚未确认，请重试或恢复购买；无需重复付款');
    } finally {
      busy = false;
      _notify();
      _drainUpdates();
    }
  }

  Future<bool> _buyCredits(String wallpaperId) async {
    if (store is! IosCreditPurchaseStore || api is! IosCreditAcquisitionApi) {
      throw const IosAcquisitionNotice('请更新 App 后购买');
    }
    final creditStore = store as IosCreditPurchaseStore;
    final creditApi = api as IosCreditAcquisitionApi;
    if (_pendingCredit != null && _pendingCreditCancelled) {
      await creditApi.cancelCreditOrder(
        _pendingCredit!.id,
        await creditStore.appIdentity(),
      );
      _pendingCredit = null;
      _pendingCreditCancelled = false;
      await _cache();
    }
    // Retry unfinished payments before starting a new Apple charge.
    for (final transaction in await store.transactions()) {
      await _deliver(transaction);
    }
    if (owns(wallpaperId)) return true;
    if (_pendingCredit != null) {
      await _accept(
        await creditApi.restoreCredits(await creditStore.appIdentity()),
      );
      if (owns(wallpaperId)) return true;
      throw const IosAcquisitionNotice('上次付款尚未确认，请在“我的”中恢复购买；无需再次付款');
    }
    if (!_creditAvailable(wallpaperId)) {
      if (_creditCurrencyMismatch(wallpaperId)) {
        throw const IosAcquisitionNotice('下载积分仅支持中国大陆商店，请切换后重试');
      }
      throw const IosAcquisitionNotice('此壁纸暂不可购买，请稍后重试');
    }
    final order = await creditApi.creditOrder(
      wallpaperId,
      await creditStore.appIdentity(),
    );
    if (order.wallpaperId != wallpaperId) {
      throw const IosAcquisitionNotice('购买订单未通过验证');
    }
    if (order.fulfilled) {
      await _accept(
        await creditApi.restoreCredits(await creditStore.appIdentity()),
      );
      return owns(wallpaperId);
    }
    if (!order.paymentAllowed) {
      _pendingCredit = order;
      await _cache();
      await _accept(
        await creditApi.restoreCredits(await creditStore.appIdentity()),
      );
      if (owns(wallpaperId)) return true;
      throw const IosAcquisitionNotice('已有付款待确认，请恢复购买；无需再次付款');
    }
    final offer = _offers.creditOffers[wallpaperId]!;
    if (offer.credits != order.credits ||
        offer.packCredits != order.packCredits ||
        offer.quantity != order.quantity ||
        _productId(wallpaperId) != order.productId) {
      await creditApi.cancelCreditOrder(
        order.id,
        await creditStore.appIdentity(),
      );
      throw const IosAcquisitionNotice('价格已更新，请刷新后重新确认');
    }
    _pendingCredit = order;
    await _cache(); // Persist before Apple; interruption cannot trigger a second charge.
    IosPurchaseResult result;
    try {
      result = await creditStore.purchaseCredits(
        order.productId,
        order.token,
        quantity: order.quantity,
        packCredits: order.packCredits,
      );
    } on PlatformException catch (failure) {
      if ({
        'PRODUCT_UNAVAILABLE',
        'CREDIT_PRICE_UNAVAILABLE',
        'INVALID_ARGUMENTS',
      }.contains(failure.code)) {
        _pendingCreditCancelled = true;
        await _cache();
        await creditApi.cancelCreditOrder(
          order.id,
          await creditStore.appIdentity(),
        );
        _pendingCredit = null;
        _pendingCreditCancelled = false;
        await _cache();
        throw IosAcquisitionNotice(failure.message ?? '此壁纸暂不可购买，请稍后重试');
      }
      rethrow;
    }
    if (result.status == 'CANCELLED') {
      _pendingCreditCancelled = true;
      await _cache();
      await creditApi.cancelCreditOrder(
        order.id,
        await creditStore.appIdentity(),
      );
      _pendingCredit = null;
      _pendingCreditCancelled = false;
      await _cache();
      return false;
    }
    if (result.status == 'PENDING') {
      throw const IosAcquisitionNotice('购买正在等待批准，完成后会自动更新权益');
    }
    final transaction = result.transaction;
    if (result.status != 'PURCHASED' ||
        transaction == null ||
        transaction.productId != order.productId ||
        transaction.productType != 'CONSUMABLE' ||
        transaction.quantity != order.quantity ||
        transaction.revoked) {
      throw const IosAcquisitionNotice('付款结果尚未确认，请恢复购买；无需再次付款');
    }
    await _deliver(transaction);
    if (!owns(wallpaperId)) {
      throw const IosAcquisitionNotice('付款已完成，请恢复购买；无需再次付款');
    }
    return true;
  }

  Future<String> restore() async {
    if (busy) throw const IosAcquisitionNotice('正在处理，请稍候');
    busy = true;
    error = null;
    _notify();
    try {
      final transactions = await store.transactions(restore: true);
      for (final transaction in transactions) {
        await _deliver(transaction);
      }
      if (api is IosCreditAcquisitionApi && store is IosCreditPurchaseStore) {
        await _accept(
          await (api as IosCreditAcquisitionApi).restoreCredits(
            await (store as IosCreditPurchaseStore).appIdentity(refresh: true),
          ),
        );
      }
      return state?.purchasedWallpaperIds.isNotEmpty == true ||
              transactions.any((value) => !value.revoked)
          ? '已恢复购买，可以再次下载'
          : '当前苹果账户没有可恢复的购买';
    } catch (_) {
      throw const IosAcquisitionNotice('恢复购买未完成，请稍后重试');
    } finally {
      busy = false;
      _notify();
      _drainUpdates();
    }
  }

  Future<void> _deliver(IosStoreTransaction transaction) async {
    // Receiving PURCHASED alone never grants local access. Keep transactions
    // unfinished on API failure so startup/Restore can retry after interruption.
    await _accept(await api.synchronize(transaction));
    if (transaction.productType == 'CONSUMABLE' &&
        transaction.accountToken != null &&
        transaction.accountToken == _pendingCredit?.token) {
      _pendingCredit = null;
      _pendingCreditCancelled = false;
      await _cache();
    }
    if (!transaction.revoked && transaction.productType != 'CONSUMABLE') {
      final mapping = state!.products.entries.where(
        (entry) => entry.value == transaction.productId,
      );
      if (mapping.isEmpty ||
          !state!.purchasedWallpaperIds.contains(mapping.first.key)) {
        throw const IosAcquisitionNotice('购买权益尚未确认');
      }
    }
    // Delivery is already durable. A failed finish must not make a confirmed
    // purchase look unsuccessful; its unfinished transaction can be retried.
    try {
      await store.finish(transaction.id);
    } catch (_) {}
  }

  void _drainUpdates() {
    if (busy || _closed || _updates.isEmpty) return;
    final transaction = _updates.removeAt(0);
    busy = true;
    _notify();
    unawaited(() async {
      try {
        await _deliver(transaction);
      } catch (_) {
        error = '购买结果尚未确认，请尝试恢复购买';
      } finally {
        busy = false;
        _notify();
        _drainUpdates();
      }
    }());
  }

  Future<void> _accept(IosAcquisitionState value) async {
    state = value;
    _catalogue = value.catalogue;
    if (_pendingFreeGeneration != null &&
        _pendingFreeGeneration != value.freeGeneration) {
      _pendingFreeId = null;
      _pendingRequestId = null;
      _pendingFreeGeneration = null;
    }
    if (_pendingFreeId != null &&
        value.freeWallpaperIds.contains(_pendingFreeId)) {
      _pendingFreeId = null;
      _pendingRequestId = null;
      _pendingFreeGeneration = null;
    }
    if (_pendingCredit != null && value.owns(_pendingCredit!.wallpaperId)) {
      _pendingCredit = null;
      _pendingCreditCancelled = false;
    }
    await _cache();
    _notify();
  }

  Future<void> _cache() => store.writeCache(
    jsonEncode({
      'state': state!.toJson(cache: true),
      if (_pendingCredit != null) 'pendingCredit': _pendingCredit!.value,
      'pendingCreditCancelled': _pendingCreditCancelled,
      if (_pendingFreeId != null)
        'pendingFree': {
          'wallpaperId': _pendingFreeId,
          'requestId': _pendingRequestId,
          'freeGeneration': _pendingFreeGeneration,
        },
    }),
  );

  void _applyApiFailure(DeviceApiError failure) {
    if (const {
      'IOS_ACQUISITION_UNAVAILABLE',
      'IOS_DEVICE_PROOF_UNAVAILABLE',
    }.contains(failure.code)) {
      unavailable = true;
      ready = false;
      error = 'Apple 验证暂不可用，请稍后重试';
      return;
    }
    if (failure.code == 'IOS_FREE_RESET_PENDING') {
      error = '测试资格重置处理中，请稍后重试';
      return;
    }
    error = '获取资格暂时无法确认，请重试';
  }

  void _notify() {
    if (!_closed) notifyListeners();
  }

  @override
  void dispose() {
    _closed = true;
    unawaited(store.stopObserving().catchError((_) {}));
    super.dispose();
  }
}
