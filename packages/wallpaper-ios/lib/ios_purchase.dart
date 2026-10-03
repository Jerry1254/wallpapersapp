import 'package:flutter/services.dart';

class IosStoreProduct {
  const IosStoreProduct(
    this.id,
    this.displayPrice, {
    required this.currencyCode,
    this.price,
    this.productType = 'NON_CONSUMABLE',
  });
  final String id, displayPrice, currencyCode;
  final String? price;
  final String productType;
  bool get isRenminbi => currencyCode == 'CNY';
}

class IosStoreTransaction {
  const IosStoreTransaction({
    required this.id,
    required this.productId,
    required this.signedTransaction,
    required this.signedAppTransaction,
    required this.deviceVerificationId,
    required this.environment,
    this.revoked = false,
    this.productType = 'NON_CONSUMABLE',
    this.quantity = 1,
    this.accountToken,
  });
  factory IosStoreTransaction.fromJson(Map<String, dynamic> value) =>
      IosStoreTransaction(
        id: value['id'] as String,
        productId: value['productId'] as String,
        signedTransaction: value['signedTransaction'] as String,
        signedAppTransaction: value['signedAppTransaction'] as String,
        deviceVerificationId: value['deviceVerificationId'] as String,
        environment: value['environment'] as String,
        revoked: value['revoked'] == true,
        productType: value['productType'] as String? ?? 'NON_CONSUMABLE',
        quantity: value['quantity'] as int? ?? 1,
        accountToken: value['accountToken'] as String?,
      );
  final String id,
      productId,
      signedTransaction,
      signedAppTransaction,
      deviceVerificationId,
      environment;
  final bool revoked;
  final String productType;
  final int quantity;
  final String? accountToken;
}

abstract interface class IosCreditPurchaseStore {
  Future<Map<String, dynamic>> appIdentity({bool refresh = false});
  Future<IosPurchaseResult> purchaseCredits(
    String productId,
    String orderToken, {
    required int quantity,
    required int packCredits,
  });
}

class IosPurchaseResult {
  const IosPurchaseResult(this.status, [this.transaction]);
  final String status;
  final IosStoreTransaction? transaction;
}

abstract interface class IosPurchaseStore {
  Future<List<IosStoreProduct>> products(Set<String> ids);
  Future<IosPurchaseResult> purchase(String productId, String accountToken);
  Future<List<IosStoreTransaction>> transactions({bool restore = false});
  Future<void> finish(String transactionId);
  Future<String?> readCache();
  Future<void> writeCache(String value);
  Future<void> observe(
    void Function(IosStoreTransaction) onTransaction, {
    void Function()? onStorefrontChanged,
  });
  Future<void> stopObserving();
}

/// StoreKit performs the payment and signature verification. No client-side
/// purchased flag is accepted as delivery authorization by the API.
class NativeIosPurchaseStore
    implements IosPurchaseStore, IosCreditPurchaseStore {
  const NativeIosPurchaseStore({
    this.testOnly = false,
    this.cacheScope = 'prod',
  });
  final bool testOnly;
  final String cacheScope;
  static const channel = MethodChannel('qingjing/ios_acquisition');
  @override
  Future<List<IosStoreProduct>> products(Set<String> ids) async {
    final values = await channel.invokeListMethod<dynamic>('products', {
      'ids': ids.toList(),
    });
    return (values ?? [])
        .map(
          (value) => IosStoreProduct(
            value['id'] as String,
            value['displayPrice'] as String,
            currencyCode: value['currencyCode'] as String,
            price: value['price'] as String?,
            productType: value['productType'] as String? ?? 'NON_CONSUMABLE',
          ),
        )
        .toList();
  }

  @override
  Future<Map<String, dynamic>> appIdentity({bool refresh = false}) async =>
      (await channel.invokeMapMethod<String, dynamic>('appIdentity', {
        'refresh': refresh,
      }))!;

  @override
  Future<IosPurchaseResult> purchaseCredits(
    String productId,
    String orderToken, {
    required int quantity,
    required int packCredits,
  }) async {
    final value = await channel.invokeMapMethod<String, dynamic>('purchase', {
      'productId': productId,
      'accountToken': orderToken,
      'quantity': quantity,
      'packCredits': packCredits,
      'testOnly': testOnly,
    });
    if (value == null) throw StateError('Missing StoreKit purchase result');
    return IosPurchaseResult(
      value['status'] as String,
      value['transaction'] == null
          ? null
          : IosStoreTransaction.fromJson(
              Map<String, dynamic>.from(value['transaction'] as Map),
            ),
    );
  }

  @override
  Future<IosPurchaseResult> purchase(
    String productId,
    String accountToken,
  ) async {
    final value = await channel.invokeMapMethod<String, dynamic>('purchase', {
      'productId': productId,
      'accountToken': accountToken,
      'testOnly': testOnly,
    });
    if (value == null) throw StateError('Missing StoreKit purchase result');
    final transaction = value['transaction'];
    return IosPurchaseResult(
      value['status'] as String,
      transaction == null
          ? null
          : IosStoreTransaction.fromJson(
              Map<String, dynamic>.from(transaction as Map),
            ),
    );
  }

  @override
  Future<List<IosStoreTransaction>> transactions({bool restore = false}) async {
    final values = await channel.invokeListMethod<dynamic>(
      restore ? 'restore' : 'transactions',
    );
    return (values ?? [])
        .map(
          (value) => IosStoreTransaction.fromJson(
            Map<String, dynamic>.from(value as Map),
          ),
        )
        .toList();
  }

  @override
  Future<void> finish(String transactionId) =>
      channel.invokeMethod<void>('finish', {'transactionId': transactionId});
  @override
  Future<String?> readCache() =>
      channel.invokeMethod<String>('readCache', {'cacheScope': cacheScope});
  @override
  Future<void> writeCache(String value) => channel.invokeMethod<void>(
    'writeCache',
    {'value': value, 'cacheScope': cacheScope},
  );
  @override
  Future<void> observe(
    void Function(IosStoreTransaction) onTransaction, {
    void Function()? onStorefrontChanged,
  }) async {
    channel.setMethodCallHandler((call) async {
      if (call.method == 'transactionUpdated') {
        onTransaction(
          IosStoreTransaction.fromJson(
            Map<String, dynamic>.from(call.arguments as Map),
          ),
        );
      } else if (call.method == 'storefrontUpdated') {
        onStorefrontChanged?.call();
      }
    });
    await channel.invokeMethod<void>('observe');
  }

  @override
  Future<void> stopObserving() async {
    channel.setMethodCallHandler(null);
    await channel.invokeMethod<void>('stopObserving');
  }
}

class NativeIosDeviceProof {
  const NativeIosDeviceProof();
  static const channel = NativeIosPurchaseStore.channel;
  Future<Map<String, dynamic>> key() async =>
      (await channel.invokeMapMethod<String, dynamic>('attestationKey'))!;
  Future<void> remember(String keyId) =>
      channel.invokeMethod<void>('rememberAttestation', {'keyId': keyId});
  Future<String> attest(String keyId, String clientData) async =>
      (await channel.invokeMethod<String>('attest', {
        'keyId': keyId,
        'clientData': clientData,
      }))!;
  Future<String> assertion(String keyId, String clientData) async =>
      (await channel.invokeMethod<String>('assertion', {
        'keyId': keyId,
        'clientData': clientData,
      }))!;
  Future<String> deviceToken() async =>
      (await channel.invokeMethod<String>('deviceCheckToken'))!;
  Future<void> resetKey() => channel.invokeMethod<void>('resetAttestationKey');
}
