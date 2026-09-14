import 'package:flutter/material.dart';
import 'package:qingjing_design_tokens/qingjing_design_tokens.dart';
import 'config/app_config.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(QingjingApp(config: AppConfig.fromBuild()));
}

class QingjingApp extends StatelessWidget {
  const QingjingApp({super.key, required this.config});
  final AppConfig config;
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
    home: const HomeShell(),
  );
}

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int index = 0;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(index == 0 ? '倾境壁纸' : '我的')),
    body: SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(QingjingWallpaperTokens.space5),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              index == 0 ? '让每一屏，都有心动' : '我的壁纸',
              style: const TextStyle(
                fontSize: QingjingWallpaperTokens.fontSizePageTitle,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: QingjingWallpaperTokens.space4),
            Text(
              index == 0 ? '目录接入准备中' : '正式设备身份接入后，展示已获得的壁纸',
              style: const TextStyle(
                color: QingjingWallpaperTokens.colorMutedInk,
              ),
            ),
          ],
        ),
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
