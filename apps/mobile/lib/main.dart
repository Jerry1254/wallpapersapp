import 'package:flutter/material.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';
import 'config/app_config.dart';
import 'catalog/catalog.dart';
import 'catalog/catalog_screen.dart';
import 'detail/help_screen.dart';
import 'device/device_session.dart';
import 'entitlements/redemption.dart';
import 'entitlements/entitlements_screen.dart';
import 'downloads/download_manager.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(QingjingApp(config: AppConfig.fromBuild()));
}

class QingjingApp extends StatelessWidget {
  const QingjingApp({super.key, required this.config, this.repository});
  final AppConfig config;
  final CatalogRepository? repository;
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '倾境壁纸',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      scaffoldBackgroundColor: QingjingWallpaperTokens.colorBackground,
      colorScheme: ColorScheme.fromSeed(
        seedColor: QingjingWallpaperTokens.colorAccent,
        surface: QingjingWallpaperTokens.colorSurface,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: QingjingWallpaperTokens.colorBackground,
        foregroundColor: QingjingWallpaperTokens.colorInk,
      ),
    ),
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
  late final redemptions = RedemptionCoordinator(
    SessionRedemptionApi(widget.sessions),
    AndroidPendingStore(),
  );
  late final downloads = DownloadManager(widget.sessions, widget.apiBase);
  @override
  void dispose() {
    downloads.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(index == 0 ? '倾境壁纸' : '我的'),
      actions: [
        IconButton(
          tooltip: '客服',
          onPressed: () => Navigator.push(
            context,
            MaterialPageRoute<void>(
              builder: (_) => const HelpScreen(customerService: true),
            ),
          ),
          icon: const Icon(Icons.support_agent),
        ),
      ],
    ),
    body: SafeArea(
      child: IndexedStack(
        index: index,
        children: [
          CatalogScreen(
            repository: widget.repository,
            redemptions: redemptions,
            downloads: downloads,
          ),
          EntitlementsScreen(
            sessions: widget.sessions,
            catalog: widget.repository,
            redemptions: redemptions,
            downloads: downloads,
          ),
        ],
      ),
    ),
    bottomNavigationBar: NavigationBar(
      selectedIndex: index,
      onDestinationSelected: (value) => setState(() => index = value),
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.home_outlined),
          selectedIcon: Icon(Icons.home),
          label: '首页',
        ),
        NavigationDestination(
          icon: Icon(Icons.person_outline),
          selectedIcon: Icon(Icons.person),
          label: '我的',
        ),
      ],
    ),
  );
}
