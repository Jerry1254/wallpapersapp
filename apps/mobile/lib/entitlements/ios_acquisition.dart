import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';
import '../device/device_session.dart';

enum IosFreeAllowance { unknown, available, used, unavailable }

class IosAcquisitionState {
  const IosAcquisitionState({
    required this.accountToken,
    required this.products,
    this.freeAllowance = IosFreeAllowance.unknown,
    this.freeWallpaperIds = const {},
    this.purchasedWallpaperIds = const {},
  });
  factory IosAcquisitionState.fromJson(Map<String, dynamic> value) {
    final token = value['accountToken'] as String;
    if (!RegExp(
      r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
    ).hasMatch(token)) {
      throw const FormatException('Invalid App Store account token');
    }
    final products = Map<String, String>.from(value['products'] as Map);
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
      'UNKNOWN' => IosFreeAllowance.unknown,
      _ => throw const FormatException('Unknown free allowance'),
    };
    return IosAcquisitionState(
      accountToken: token,
      products: products,
      freeAllowance: allowance,
      freeWallpaperIds: free,
      purchasedWallpaperIds: paid,
    );
  }
  final String accountToken;
  final Map<String, String> products;
  final IosFreeAllowance freeAllowance;
  final Set<String> freeWallpaperIds, purchasedWallpaperIds;
  bool owns(String id) =>
      freeWallpaperIds.contains(id) || purchasedWallpaperIds.contains(id);
  Map<String, dynamic> toJson({bool cache = false}) => {
    'accountToken': accountToken, 'products': products,
    // A cached AVAILABLE response never makes a new device eligible offline.
    'freeAllowance': cache ? 'UNKNOWN' : freeAllowance.name.toUpperCase(),
    'freeWallpaperIds': freeWallpaperIds.toList(),
    'purchasedWallpaperIds': purchasedWallpaperIds.toList(),
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
}

/// Proposed API is intentionally separate from the deployed 2.13 contract.
/// Enable its build flag only after the server implements this interface.
class SessionIosAcquisitionApi implements IosAcquisitionApi {
  SessionIosAcquisitionApi(
    this.sessions, {
    this.proof = const NativeIosDeviceProof(),
  });
  final DeviceSessionManager sessions;
  final NativeIosDeviceProof proof;
  Future<String>? _key;
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
  }) async {
    try {
      final key = await (_key ??= _enroll());
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
        'deviceToken': await proof.deviceToken(),
        'wallpaperId': ?wallpaperId,
        'requestId': ?requestId,
      });
      // The server hashes the exact UTF-8 request bytes, not reserialized JSON.
      final assertion = await proof.assertion(key, body);
      final response = await sessions.authenticated(
        path,
        method: 'POST',
        body: body,
        signed: true,
        headers: {
          'X-App-Attest-Key-Id': key,
          'X-App-Attest-Assertion': assertion,
        },
      );
      return IosAcquisitionState.fromJson(response);
    } catch (_) {
      _key =
          null; // Retry transient enrollment failure, never fall back to a local ID.
      rethrow;
    }
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
    try {
      final key = await (_key ??= _enroll());
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
      });
      final assertion = await proof.assertion(key, body);
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
    } catch (_) {
      _key = null;
      rethrow;
    }
  }
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
  String? error;
  String? _pendingFreeId, _pendingRequestId;
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
    _notify();
    try {
      await _accept(await api.state());
      final found = await store.products(state!.products.values.toSet());
      products = {for (final product in found) product.id: product};
      ready = true;
      for (final transaction in await store.transactions()) {
        await _deliver(transaction);
      }
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
    if (busy) return '正在确认资格';
    if (!ready || error != null) return '重试获取资格';
    if (state!.freeAllowance == IosFreeAllowance.unknown) return '重试获取资格';
    if (state!.freeAllowance == IosFreeAllowance.available) return '首次免费获取';
    final product = products[state!.products[wallpaperId]];
    return product == null ? '暂不可购买' : '${product.displayPrice} 购买并下载';
  }

  bool canAcquire(String id) =>
      !busy &&
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
    if (owns(wallpaperId)) return true;
    if (!ready ||
        error != null ||
        state!.freeAllowance == IosFreeAllowance.unknown) {
      await refresh();
      return false;
    }
    busy = true;
    error = null;
    _notify();
    try {
      if (state!.freeAllowance == IosFreeAllowance.available ||
          _pendingFreeId != null) {
        if (_pendingFreeId != null && _pendingFreeId != wallpaperId) {
          throw const IosAcquisitionNotice('上次免费获取尚未确认，请回到原壁纸重试');
        }
        _pendingFreeId = wallpaperId;
        _pendingRequestId ??= requestUuid();
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
      if (failure.code == 'IOS_FREE_ALLOWANCE_USED' && _pendingFreeId != null) {
        _pendingFreeId = null;
        _pendingRequestId = null;
        await _cache();
        error = '免费资格已使用，请重新确认获取方式';
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
    if (_pendingFreeId != null &&
        value.freeWallpaperIds.contains(_pendingFreeId)) {
      _pendingFreeId = null;
      _pendingRequestId = null;
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
        },
    }),
  );
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
