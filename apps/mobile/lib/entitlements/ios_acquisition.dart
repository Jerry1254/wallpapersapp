import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
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

class IosAcquisitionState {
  const IosAcquisitionState({
    required this.installationId,
    required this.accountToken,
    required this.freeGeneration,
    required this.products,
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
    final products = <String, String>{};
    final productValue = value['products'];
    if (productValue is List) {
      for (final item in productValue) {
        final product = Map<String, dynamic>.from(item as Map);
        final wallpaperId = product['wallpaperId'] as String;
        final productId = product['productId'] as String;
        if (products.putIfAbsent(wallpaperId, () => productId) != productId) {
          throw const FormatException('Duplicate wallpaper product mapping');
        }
      }
    } else if (productValue is Map) {
      // Accept the previous on-device cache shape during the one-time upgrade.
      products.addAll(Map<String, String>.from(productValue));
    } else {
      throw const FormatException('Invalid wallpaper product mapping');
    }
    final free = Set<String>.from(value['freeWallpaperIds'] as List);
    final paid = Set<String>.from(value['purchasedWallpaperIds'] as List);
    if ([
          ...products.keys,
          ...free,
          ...paid,
        ].any((id) => !RegExp(r'^[1-9][0-9]*$').hasMatch(id)) ||
        products.values.any((id) => id.isEmpty) ||
        products.values.toSet().length != products.length) {
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
    'products': [
      for (final entry in products.entries)
        {'wallpaperId': entry.key, 'productId': entry.value},
    ],
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
  Future<IosAcquisitionState> claimFree(String wallpaperId, String requestId);
  Future<IosAcquisitionState> synchronize(IosStoreTransaction transaction);
  Future<IosAcquisitionState> completeFreeReset(IosPendingFreeReset reset);
}

/// OpenAPI 2.14 acquisition adapter. Keep its build flag disabled until the
/// matching server configuration and database migrations are deployed.
class SessionIosAcquisitionApi implements IosAcquisitionApi {
  SessionIosAcquisitionApi(
    this.sessions, {
    this.proof = const NativeIosDeviceProof(),
  });
  final DeviceSessionManager sessions;
  final NativeIosDeviceProof proof;
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
  IosAcquisitionState? state;
  Map<String, IosStoreProduct> products = {};
  bool busy = false, ready = false;
  bool unavailable = false;
  String? error;
  String? _pendingFreeId, _pendingRequestId;
  int? _pendingFreeGeneration;
  Future<void>? _initialization;
  final List<IosStoreTransaction> _updates = [];
  bool _closed = false;
  bool owns(String id) => state?.owns(id) == true;

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
      });
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
      final found = await store.products(state!.products.values.toSet());
      products = {for (final product in found) product.id: product};
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

  String label(String wallpaperId) {
    if (owns(wallpaperId)) return '再次下载';
    if (unavailable) return 'Apple 验证暂不可用';
    if (busy) return '正在确认资格';
    if (state?.freeAllowance == IosFreeAllowance.pendingReset) {
      return '测试资格重置处理中';
    }
    if (!ready || error != null) return '重试获取资格';
    if (state!.freeAllowance == IosFreeAllowance.unknown) return '重试获取资格';
    if (state!.freeAllowance == IosFreeAllowance.available) return '首次免费获取';
    final product = products[state!.products[wallpaperId]];
    return product == null ? '暂不可购买' : '${product.displayPrice} 购买并下载';
  }

  bool canAcquire(String id) =>
      !busy &&
      !unavailable &&
      state?.freeAllowance != IosFreeAllowance.pendingReset &&
      (owns(id) ||
          !ready ||
          error != null ||
          state!.freeAllowance == IosFreeAllowance.unknown ||
          state!.freeAllowance == IosFreeAllowance.available ||
          products.containsKey(state!.products[id]));

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
    busy = true;
    error = null;
    unavailable = false;
    _notify();
    try {
      if (state!.freeAllowance == IosFreeAllowance.available ||
          _pendingFreeId != null) {
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
      } else {
        final productId = state!.products[wallpaperId];
        if (productId == null || !products.containsKey(productId)) {
          throw const IosAcquisitionNotice('此壁纸暂不可购买，请稍后重试');
        }
        final result = await store.purchase(productId, state!.accountToken);
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
      return transactions.any((value) => !value.revoked)
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
    if (!transaction.revoked) {
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
    await _cache();
    _notify();
  }

  Future<void> _cache() => store.writeCache(
    jsonEncode({
      'state': state!.toJson(cache: true),
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
