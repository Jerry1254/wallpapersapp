import 'privacy/remote_policies.dart';
import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'design_system/qj_theme.dart';
import 'design_system/qj_components.dart';
import 'config/app_config.dart';
import 'config/app_branding.dart';
import 'config/internal_tls.dart';
import 'catalog/catalog.dart';
import 'catalog/catalog_screen.dart';
import 'detail/detail_preview.dart';
import 'device/device_session.dart';
import 'entitlements/redemption.dart';
import 'entitlements/entitlements_screen.dart';
import 'entitlements/ios_acquisition.dart';
import 'package:wallpaper_ios/wallpaper_ios.dart';
import 'downloads/download_manager.dart';
import 'downloads/global_download_dialog.dart';
import 'privacy/privacy_gate.dart';
import 'updates/app_updates.dart';
import 'updates/app_update_gate.dart';
import 'package:wallpaper_android/wallpaper_android.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(qjSystemUiOverlayStyle);
  final config = AppConfig.fromBuild();
  configureInternalTls(config);
  runApp(QingjingApp(config: config));
}

class QingjingApp extends StatefulWidget {
  const QingjingApp({
    super.key,
    required this.config,
    this.repository,
    this.updateController,
    this.policySource,
    this.privacyConsentStore = const PlatformPrivacyConsentStore(),
  });
  final AppConfig config;
  final CatalogRepository? repository;
  final AppUpdateController? updateController;
  final PrivacyConsentStore privacyConsentStore;
  final PolicySource? policySource;
  @override
  State<QingjingApp> createState() => _QingjingAppState();
}

class _QingjingAppState extends State<QingjingApp> {
  final appNavigatorKey = GlobalKey<NavigatorState>();
  late final updates =
      widget.updateController ?? AppUpdateController(widget.config.apiBase);
  late final DeviceSessionManager sessions = DeviceSessionManager(
    HttpDeviceTransport(
      widget.config.apiBase,
      versionHeaders: updates.versionHeaders,
      onUpdateRequired: updates.requireFromServer,
    ),
  );
  late final AndroidWallpaperPlayback playback =
      const AndroidWallpaperPlayback();
  late final CatalogRepository repository =
      widget.repository ??
      HttpCatalogRepository(
        widget.config.apiBase,
        sessions: sessions,
        authenticatedMedia: widget.config.isOffline,
      );

  @override
  void dispose() {
    if (widget.updateController == null) updates.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AppBrandingScope(
    branding: widget.config.branding,
    child: MaterialApp(
      title: widget.config.branding.appName,
      debugShowCheckedModeBanner: false,
      navigatorObservers: [detailPreviewRouteObserver],
      navigatorKey: appNavigatorKey,
      builder: (context, child) => AppUpdateOverlay(
        controller: updates,
        navigatorKey: appNavigatorKey,
        child: child!,
      ),
      theme: QjTheme.light,
      home: PrivacyGate(
        store: widget.privacyConsentStore,
        policySource: widget.config.isOffline
            ? null
            : widget.policySource ?? RemotePolicySource(widget.config.apiBase),
        builder: (_) => AppUpdateBootstrap(
          controller: updates,
          builder: (_) => HomeShell(
            repository: repository,
            sessions: sessions,
            apiBase: widget.config.apiBase,
            playback: playback,
            labMode: widget.config.environment == 'lab',
          ),
        ),
      ),
    ),
  );
}

class HomeShell extends StatefulWidget {
  const HomeShell({
    super.key,
    required this.repository,
    required this.sessions,
    required this.apiBase,
    required this.playback,
    this.labMode = false,
  });
  final Uri apiBase;
  final DeviceSessionManager sessions;
  final CatalogRepository repository;
  final AndroidWallpaperPlayback playback;
  final bool labMode;
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int index = 0;
  bool downloadDialogVisible = false;
  late final redemptions = RedemptionCoordinator(
    SessionRedemptionApi(widget.sessions),
    platformPendingStore(),
  );
  late final downloads = DownloadManager(widget.sessions, widget.apiBase);
  late final IosAcquisitionController? iosAcquisition =
      Platform.isIOS && AppConfig.iosAcquisitionEnabled
      ? IosAcquisitionController(
          SessionIosAcquisitionApi(widget.sessions),
          NativeIosPurchaseStore(cacheScope: widget.apiBase.toString()),
        )
      : null;
  @override
  void initState() {
    super.initState();
    downloads.addListener(_downloadChanged);
    if (iosAcquisition != null) unawaited(iosAcquisition!.initialize());
    if (Platform.isAndroid) {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        try {
          const native = AndroidTrialPreview();
          final saved = await native.recover();
          // Trial entry is hidden for Android 1.0; silently clear old sessions.
          if (saved != null) await native.discard(saved['trialId'] as String);
        } catch (_) {}
      });
    }
  }

  @override
  void dispose() {
    downloads.removeListener(_downloadChanged);
    downloads.dispose();
    iosAcquisition?.dispose();
    super.dispose();
  }

  void _downloadChanged() {
    final state = downloads.value;
    if (state.busy && !downloadDialogVisible) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && downloads.value.busy && !downloadDialogVisible) {
          unawaited(_showDownloadDialog());
        }
      });
      return;
    }
  }

  Future<void> _showDownloadDialog() async {
    downloadDialogVisible = true;
    try {
      await showDialog<void>(
        context: context,
        useRootNavigator: true,
        barrierDismissible: false,
        builder: (_) => GlobalDownloadDialog(manager: downloads),
      );
    } finally {
      downloadDialogVisible = false;
    }
  }

  @override
  Widget build(BuildContext context) => _content();

  Widget _content() => AnnotatedRegion<SystemUiOverlayStyle>(
    value: qjSystemUiOverlayStyle,
    child: Scaffold(
      body: SafeArea(
        child: IndexedStack(
          index: index,
          children: [
            TickerMode(
              enabled: index == 0,
              child: CatalogScreen(
                repository: widget.repository,
                redemptions: redemptions,
                downloads: downloads,
                playback: widget.playback,
                labMode: widget.labMode,
                iosAcquisition: iosAcquisition,
                onTab: (value) => setState(() => index = value),
              ),
            ),
            TickerMode(
              enabled: index == 1,
              child: EntitlementsScreen(
                sessions: widget.sessions,
                catalog: widget.repository,
                redemptions: redemptions,
                downloads: downloads,
                playback: widget.playback,
                active: index == 1,
                iosAcquisition: iosAcquisition,
                onHome: () => setState(() => index = 0),
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        minimum: const EdgeInsets.fromLTRB(T.space5, 0, T.space5, T.space5),
        child: Center(
          heightFactor: 1,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 390),
            child: QjBottomNav(
              selectedIndex: index,
              onSelected: (value) => setState(() => index = value),
              items: const [
                QjNavItem('首页', 'house'),
                QjNavItem('我的', 'images'),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
