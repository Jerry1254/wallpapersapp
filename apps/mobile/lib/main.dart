import 'package:flutter/material.dart';
import 'design_system/qj_theme.dart';
import 'design_system/qj_components.dart';
import 'design_system/ui_spec_screen.dart';
import 'config/app_config.dart';
import 'config/internal_tls.dart';
import 'catalog/catalog.dart';
import 'catalog/catalog_screen.dart';
import 'detail/help_screen.dart';
import 'detail/detail_preview.dart';
import 'device/device_session.dart';
import 'entitlements/redemption.dart';
import 'entitlements/entitlements_screen.dart';
import 'downloads/download_manager.dart';
import 'package:wallpaper_android/wallpaper_android.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final config = AppConfig.fromBuild();
  configureInternalTls(config);
  runApp(QingjingApp(config: config));
}

class QingjingApp extends StatelessWidget {
  const QingjingApp({super.key, required this.config, this.repository});
  final AppConfig config;
  final CatalogRepository? repository;
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '倾境壁纸',
    debugShowCheckedModeBanner: false,
    navigatorObservers: [detailPreviewRouteObserver],
    theme: QjTheme.light,
    home: HomeShell(
      repository: repository ?? HttpCatalogRepository(config.apiBase),
      sessions: DeviceSessionManager(HttpDeviceTransport(config.apiBase)),
      apiBase: config.apiBase,
    ),
  );
}

class HomeShell extends StatefulWidget {
  const HomeShell({
    super.key,
    required this.repository,
    required this.sessions,
    required this.apiBase,
  });
  final Uri apiBase;
  final DeviceSessionManager sessions;
  final CatalogRepository repository;
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int index = 0;
  bool uiVisited = false;
  late final redemptions = RedemptionCoordinator(
    SessionRedemptionApi(widget.sessions),
    AndroidPendingStore(),
  );
  late final downloads = DownloadManager(widget.sessions, widget.apiBase);
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      try {
        const native = AndroidTrialPreview();
        final saved = await native.recover();
        // Trial entry is hidden for Android 1.0; silently clear old sessions.
        if (saved != null) await native.discard(saved['trialId'] as String);
      } catch (_) {}
    });
  }

  @override
  void dispose() {
    downloads.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: index == 2
        ? AppBar(
            title: const Text('UI 规范'),
            actions: [
              IconButton(
                tooltip: '微信客服',
                onPressed: () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) => const HelpScreen(customerService: true),
                  ),
                ),
                icon: const QjIcon('headphones'),
              ),
            ],
          )
        : null,
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
              playback: const AndroidWallpaperPlayback(),
              onTab: (value) => setState(() {
                index = value;
                if (value == 2) uiVisited = true;
              }),
            ),
          ),
          TickerMode(
            enabled: index == 1,
            child: EntitlementsScreen(
              sessions: widget.sessions,
              catalog: widget.repository,
              redemptions: redemptions,
              downloads: downloads,
              playback: const AndroidWallpaperPlayback(),
              active: index == 1,
              onHome: () => setState(() => index = 0),
            ),
          ),
          TickerMode(
            enabled: index == 2,
            child: uiVisited ? const UiSpecScreen() : const SizedBox.shrink(),
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
            onSelected: (value) => setState(() {
              index = value;
              if (value == 2) uiVisited = true;
            }),
            items: const [
              QjNavItem('首页', 'house'),
              QjNavItem('我的', 'images'),
              QjNavItem('UI 规范', 'palette'),
            ],
          ),
        ),
      ),
    ),
  );
}
