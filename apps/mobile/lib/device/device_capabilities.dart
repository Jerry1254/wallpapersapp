import 'dart:async';
import 'dart:convert';
import 'package:wallpaper_android/wallpaper_android.dart';
import 'package:wallpaper_platform_interface/wallpaper_platform_interface.dart';
import 'device_session.dart';

class DeviceDeliveryCapability {
  const DeviceDeliveryCapability({
    required this.deliveryPlatform,
    required this.resourceType,
    required this.runtimeOsVersion,
    required this.placements,
    this.evidence = 'SYSTEM_PROBE',
  });

  factory DeviceDeliveryCapability.fromJson(Map<String, dynamic> json) =>
      DeviceDeliveryCapability(
        deliveryPlatform: json['deliveryPlatform'] as String,
        resourceType: json['resourceType'] as String,
        runtimeOsVersion: json['runtimeOsVersion'] as String,
        placements: Set<String>.from(json['placements'] as List),
      );

  final String deliveryPlatform, resourceType, runtimeOsVersion, evidence;
  final Set<String> placements;

  Map<String, dynamic> toJson() => {
    'deliveryPlatform': deliveryPlatform,
    'resourceType': resourceType,
    'runtimeOsVersion': runtimeOsVersion,
    'placements': placements.toList()..sort(),
    'evidence': evidence,
  };

  bool matches(String platform, String type) =>
      deliveryPlatform == platform && resourceType == type;
}

class DeviceCapabilityProfile {
  const DeviceCapabilityProfile({
    required this.version,
    required this.profileHash,
    required this.effectiveCapabilities,
    required this.recheckRequired,
  });

  factory DeviceCapabilityProfile.fromJson(Map<String, dynamic> json) {
    final hash = json['profileHash'] as String? ?? '';
    if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(hash)) {
      throw const FormatException('Invalid capability profile hash');
    }
    return DeviceCapabilityProfile(
      version: json['version'] as int,
      profileHash: hash,
      effectiveCapabilities: ((json['effectiveCapabilities'] as List?) ?? [])
          .map(
            (item) => DeviceDeliveryCapability.fromJson(
              Map<String, dynamic>.from(item as Map),
            ),
          )
          .toList(growable: false),
      recheckRequired: json['recheckRequired'] == true,
    );
  }

  final int version;
  final String profileHash;
  final List<DeviceDeliveryCapability> effectiveCapabilities;
  final bool recheckRequired;
}

abstract interface class DeviceCapabilityProbe {
  Future<WallpaperCapabilities> probe();
}

class AndroidDeviceCapabilityProbe implements DeviceCapabilityProbe {
  const AndroidDeviceCapabilityProbe(this.playback);
  final AndroidWallpaperPlayback playback;
  @override
  Future<WallpaperCapabilities> probe() => playback.capabilities();
}

/// Owns the API-015 probe/report lifecycle for one App process.
class DeviceCapabilityManager {
  DeviceCapabilityManager(
    this.sessions, {
    DeviceCapabilityProbe? probe,
    this.probeVersion = 1,
  }) : probe =
           probe ??
           const AndroidDeviceCapabilityProbe(AndroidWallpaperPlayback());

  final DeviceSessionManager sessions;
  final DeviceCapabilityProbe probe;
  final int probeVersion;
  DeviceCapabilityProfile? _profile;
  Future<DeviceCapabilityProfile>? _pending;

  DeviceCapabilityProfile? get profile => _profile;

  Future<DeviceCapabilityProfile> ensureCurrent({bool force = false}) {
    if (!force && _profile != null && !_profile!.recheckRequired) {
      return Future.value(_profile);
    }
    if (_pending != null) return _pending!;
    final operation = _report();
    _pending = operation;
    return operation.whenComplete(() {
      if (identical(_pending, operation)) _pending = null;
    });
  }

  Future<T> withProfile<T>(Future<T> Function() request) async {
    await ensureCurrent();
    try {
      return await request();
    } on DeviceApiError catch (error) {
      if (error.status != 428 ||
          error.code != 'DEVICE_CAPABILITY_PROFILE_REQUIRED') {
        rethrow;
      }
      await ensureCurrent(force: true);
      return request();
    }
  }

  Future<void> recordSuccessfulSet(String platform, String type) async {
    await _report(successfulPair: (platform, type));
  }

  Future<void> recordUnsupported(String platform, String type) async {
    await _report(excludedPair: (platform, type));
  }

  Future<DeviceCapabilityProfile> _report({
    (String, String)? successfulPair,
    (String, String)? excludedPair,
  }) async {
    final detected = await probe.probe();
    if (detected.platform != ClientPlatform.android) {
      throw const DeviceApiError(0, 'DEVICE_PROVIDER_NOT_ALLOWED');
    }
    final capabilities = _reported(detected, successfulPair, excludedPair);
    final osVersion = _text(detected.osVersion, 32, fallback: 'UNKNOWN');
    final body = jsonEncode({
      'hostOsFamily': detected.hostOsFamily,
      'hostOsVersion': osVersion,
      'sdkInt': detected.sdkInt,
      'manufacturer': _text(detected.manufacturer, 64, fallback: 'UNKNOWN'),
      'model': _text(detected.model, 96, fallback: 'UNKNOWN'),
      'executionMode': detected.executionMode,
      'probeVersion': probeVersion,
      'probedAt': instantString(DateTime.now()),
      'featureFlags': detected.parallaxSensorAvailable
          ? ['PARALLAX_SENSOR']
          : <String>[],
      'capabilities': capabilities.map((item) => item.toJson()).toList(),
    });
    final response = await sessions.authenticated(
      '/device/me/capabilities',
      method: 'PUT',
      body: body,
      signed: true,
    );
    final profile = DeviceCapabilityProfile.fromJson(response);
    _profile = profile;
    return profile;
  }

  List<DeviceDeliveryCapability> _reported(
    WallpaperCapabilities detected,
    (String, String)? successfulPair,
    (String, String)? excludedPair,
  ) {
    final runtime = (detected.sdkInt ?? 1).toString();
    final result = <DeviceDeliveryCapability>[];
    void add(WallpaperEffect effect, String platform, String type) {
      if (excludedPair == (platform, type)) return;
      if (effect == WallpaperEffect.parallax &&
          !detected.parallaxSensorAvailable) {
        return;
      }
      final supported = detected.targets[effect] ?? const {};
      final placements = <String>{};
      if (supported.contains(WallpaperTarget.home) ||
          supported.contains(WallpaperTarget.both)) {
        placements.add('HOME');
      }
      if (supported.contains(WallpaperTarget.lock) ||
          supported.contains(WallpaperTarget.both)) {
        placements.add('LOCK');
      }
      if (placements.isEmpty) return;
      result.add(
        DeviceDeliveryCapability(
          deliveryPlatform: platform,
          resourceType: type,
          runtimeOsVersion: runtime,
          placements: placements,
          evidence: successfulPair == (platform, type)
              ? 'SUCCESSFUL_SET'
              : 'SYSTEM_PROBE',
        ),
      );
    }

    add(WallpaperEffect.parallax, 'ANDROID', 'LAYER_PARALLAX');
    add(WallpaperEffect.video, 'ANDROID', 'VIDEO');
    add(WallpaperEffect.staticImage, 'UNIVERSAL', 'STATIC_IMAGE');
    return result;
  }

  String _text(String? value, int max, {required String fallback}) {
    final normalized = value?.trim() ?? '';
    final selected = normalized.isEmpty ? fallback : normalized;
    return selected.length <= max ? selected : selected.substring(0, max);
  }
}
