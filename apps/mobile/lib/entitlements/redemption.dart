import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import '../device/device_session.dart';

class PendingRedemption {
  const PendingRedemption(this.key, this.wallpaperId, this.bodyHash);
  final String key, wallpaperId, bodyHash;
  Map<String, String> toJson() => {
    'key': key,
    'wallpaperId': wallpaperId,
    'bodyHash': bodyHash,
  };
  factory PendingRedemption.fromJson(Map<String, dynamic> value) {
    final key = value['key'] as String,
        id = value['wallpaperId'] as String,
        hash = value['bodyHash'] as String;
    if (!RegExp(
          r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
        ).hasMatch(key) ||
        !RegExp(r'^[1-9][0-9]*$').hasMatch(id) ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(hash)) {
      throw const FormatException('Invalid pending redemption');
    }
    return PendingRedemption(key, id, hash);
  }
}

abstract interface class PendingStore {
  Future<PendingRedemption?> read();
  Future<void> write(PendingRedemption? value);
}

class AndroidPendingStore implements PendingStore {
  static const channel = MethodChannel('qingjing/wallpaper_android');
  @override
  Future<PendingRedemption?> read() async {
    final value = await channel.invokeMethod<String>('readPendingRedemption');
    return value == null
        ? null
        : PendingRedemption.fromJson(jsonDecode(value) as Map<String, dynamic>);
  }

  @override
  Future<void> write(PendingRedemption? value) => channel.invokeMethod<void>(
    'writePendingRedemption',
    {'value': value == null ? null : jsonEncode(value.toJson())},
  );
}

abstract interface class RedemptionApi {
  Future<Map<String, dynamic>> submit(PendingRedemption pending, String body);
  Future<Map<String, dynamic>> confirm(String key);
}

class SessionRedemptionApi implements RedemptionApi {
  SessionRedemptionApi(this.sessions);
  final DeviceSessionManager sessions;
  @override
  Future<Map<String, dynamic>> submit(PendingRedemption pending, String body) =>
      sessions.authenticated(
        '/device/redemptions',
        method: 'POST',
        body: body,
        signed: true,
        headers: {'Idempotency-Key': pending.key},
        accepted: {422},
      );
  @override
  Future<Map<String, dynamic>> confirm(String key) =>
      sessions.authenticated('/device/redemptions/$key');
}

class RedemptionNotice implements Exception {
  const RedemptionNotice(this.message);
  final String message;
}

/// One instance per installation UI: serialize every redemption and recovery.
class RedemptionCoordinator {
  RedemptionCoordinator(this.api, this.store);
  final RedemptionApi api;
  final PendingStore store;
  bool _busy = false;
  Future<String> _exclusive(Future<String> Function() action) async {
    if (_busy) throw const RedemptionNotice('正在处理兑换，请稍候');
    _busy = true;
    try {
      return await action();
    } finally {
      _busy = false;
    }
  }

  Future<String> redeem(String id, String code) => _exclusive(() async {
    final normalized = code.replaceAll(RegExp(r'[-\s]'), '').toUpperCase();
    if (!RegExp(r'^[1-9][0-9]*$').hasMatch(id) ||
        !RegExp(r'^[A-Z0-9]{20}$').hasMatch(normalized)) {
      throw const RedemptionNotice('请输入 20 位兑换码');
    }
    final body = jsonEncode({'wallpaperId': id, 'code': normalized});
    final hash = sha256.convert(utf8.encode(body)).toString();
    final previous = await store.read();
    if (previous != null &&
        (previous.wallpaperId != id || previous.bodyHash != hash)) {
      throw const RedemptionNotice('上次兑换结果尚未确认，请先确认原请求；不要更换作品或兑换码');
    }
    final pending = previous ?? PendingRedemption(requestUuid(), id, hash);
    await store.write(
      pending,
    ); // Durable before dispatch; code never persisted.
    try {
      return await _finish(pending, await api.submit(pending, body));
    } on DeviceApiError catch (e) {
      // A retry can fail before reaching an earlier committed transaction.
      // Only a first dispatch with a definite pre-commit rejection is discardable.
      if (previous == null && {400, 401, 403, 404, 429}.contains(e.status)) {
        await store.write(null);
        throw RedemptionNotice(e.message);
      }
      throw RedemptionNotice('${e.message}。请确认原兑换结果');
    } on RedemptionNotice {
      rethrow;
    } catch (_) {
      throw const RedemptionNotice('结果尚未确认，请查询原兑换结果，或用原兑换码重试');
    }
  });
  Future<String> confirm() => _exclusive(() async {
    final pending = await store.read();
    if (pending == null) return '没有待确认的兑换';
    try {
      return await _finish(pending, await api.confirm(pending.key));
    } on RedemptionNotice {
      rethrow;
    } catch (_) {
      throw const RedemptionNotice('尚未查到最终结果；请稍后确认，或回到原作品输入原兑换码重试');
    }
  });
  Future<String> _finish(
    PendingRedemption pending,
    Map<String, dynamic> result,
  ) async {
    if (result['idempotencyKey'] != pending.key ||
        result['status'] == 'PROCESSING') {
      throw const RedemptionNotice('兑换结果尚未确认，请稍后查询');
    }
    final message = switch (result['result']) {
      'GRANTED' => '兑换成功，已获得壁纸权益',
      'ALREADY_OWNED' => '已拥有此壁纸，本次未扣额度',
      'CODE_NOT_FOUND' => '兑换码无效，本次未扣额度',
      'CODE_EXHAUSTED' => '兑换码额度已用尽，本次未扣额度',
      'WALLPAPER_UNAVAILABLE' => '此壁纸暂不可兑换，本次未扣额度',
      'FAILED' => '兑换未成功，请联系客服',
      _ => null,
    };
    if (message == null) throw const RedemptionNotice('返回结果无法确认，请稍后查询');
    await store.write(null);
    return message;
  }
}
