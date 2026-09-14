import 'package:flutter/material.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';
import 'config/app_config.dart';
import 'catalog/catalog.dart';
import 'catalog/catalog_screen.dart';
import 'detail/help_screen.dart';

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
    ),
  );
}

class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.repository});
  final CatalogRepository repository;
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int index = 0;
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
                const Text('正式设备身份接入后，展示已获得的壁纸'),
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
