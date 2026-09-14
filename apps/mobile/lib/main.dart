import 'package:flutter/material.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';
import 'config/app_config.dart';
import 'catalog/catalog.dart';
import 'catalog/catalog_screen.dart';
import 'detail/help_screen.dart';
import 'device/device_session.dart';

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
    ),
  );
}

class HomeShell extends StatefulWidget {
  const HomeShell({
    super.key,
    required this.repository,
    required this.sessions,
  });
  final DeviceSessionManager sessions;
  final CatalogRepository repository;
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int index = 0;
  bool connecting = false;
  String identityMessage = '连接设备身份后，读取已获得的壁纸';
  Future<void> connectIdentity() async {
    setState(() {
      connecting = true;
    });
    try {
      await widget.sessions.authenticated('/device/me/entitlements');
      if (mounted) {
        setState(() {
          identityMessage = 'Android 安装身份已验证，权益服务连接正常';
        });
      }
    } catch (error) {
      if (mounted) {
        setState(() {
          identityMessage = error is DeviceApiError
              ? error.message
              : '安装凭据暂时不可用，请重试或联系客服';
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          connecting = false;
        });
      }
    }
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
          CatalogScreen(repository: widget.repository),
          Padding(
            padding: EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '我的壁纸',
                  style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700),
                ),
                SizedBox(height: 16),
                Text(identityMessage),
                FilledButton(
                  onPressed: connecting ? null : connectIdentity,
                  child: Text(connecting ? '正在验证…' : '连接设备身份'),
                ),
                TextButton(
                  onPressed: () => Navigator.push(
                    context,
                    MaterialPageRoute<void>(builder: (_) => const HelpScreen()),
                  ),
                  child: const Text('壁纸教程'),
                ),
              ],
            ),
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
