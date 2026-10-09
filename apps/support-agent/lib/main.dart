import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qingjing_customer_support/customer_support.dart';
import 'session.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const AgentApp());
}

class AgentApp extends StatefulWidget {
  const AgentApp({super.key});
  @override
  State<AgentApp> createState() => _AgentAppState();
}

class _AgentAppState extends State<AgentApp> {
  late final session = AgentSession(endpoint());
  SupportChat? chat;
  bool restoring = true;
  String restoreError = '';
  final navigator = GlobalKey<NavigatorState>();
  Uri endpoint() {
    const value = String.fromEnvironment(
      'API_BASE_URL',
      defaultValue: 'https://api.invalid/api/v1',
    );
    final uri = Uri.parse(value);
    if (uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.host.isEmpty ||
        (uri.scheme != 'https' && !(kDebugMode && uri.scheme == 'http'))) {
      throw ArgumentError('客服 API 地址无效');
    }
    return uri;
  }

  @override
  void initState() {
    super.initState();
    session.onExpired = expired;
    unawaited(restore());
  }

  Future<void> restore() async {
    try {
      if (await session.restore()) await ready();
    } catch (cause) {
      restoreError = supportError(cause);
    } finally {
      if (mounted) setState(() => restoring = false);
    }
  }

  Future<void> ready() async {
    final api = session.api();
    final next = SupportChat(
      api,
      await FileSupportStore.scoped(await api.cacheScope()),
    );
    await next.initialize();
    if (!mounted) {
      next.dispose();
      return;
    }
    chat?.dispose();
    setState(() => chat = next);
  }

  void expired() {
    if (!mounted) return;
    chat?.dispose();
    setState(() => chat = null);
    navigator.currentState?.popUntil((route) => route.isFirst);
  }

  Future<void> logout() async {
    try {
      await session.logout();
    } finally {
      expired();
    }
  }

  @override
  void dispose() {
    chat?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorKey: navigator,
    title: '倾境客服',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xfff3a81e),
        primary: const Color(0xffbd7900),
        surface: Colors.white,
      ),
      scaffoldBackgroundColor: const Color(0xfffaf9f6),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.white,
        foregroundColor: Color(0xff292722),
        surfaceTintColor: Colors.transparent,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: const Color(0xffffbb3c),
          foregroundColor: const Color(0xff302207),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
    ),
    home: restoring
        ? const Scaffold(body: Center(child: CircularProgressIndicator()))
        : chat == null
        ? AgentLogin(session: session, ready: ready, initialError: restoreError)
        : SupportAgentScreen(
            key: ValueKey(session.adminId),
            chat: chat!,
            logout: logout,
            notifySound: () => unawaited(
              const MethodChannel(
                'qingjing/support-agent',
              ).invokeMethod<void>('beep').catchError((Object _) {}),
            ),
          ),
  );
}

class AgentLogin extends StatefulWidget {
  const AgentLogin({
    super.key,
    required this.session,
    required this.ready,
    this.initialError = '',
  });
  final AgentSession session;
  final Future<void> Function() ready;
  final String initialError;
  @override
  State<AgentLogin> createState() => _AgentLoginState();
}

class _AgentLoginState extends State<AgentLogin> {
  final username = TextEditingController(), password = TextEditingController();
  bool busy = false;
  late String error = widget.initialError;
  Future<void> login() async {
    if (busy) return;
    if (username.text.trim().isEmpty || password.text.isEmpty) {
      setState(() => error = '请输入后台账号和密码');
      return;
    }
    setState(() {
      busy = true;
      error = '';
    });
    try {
      await widget.session.login(username.text.trim(), password.text);
      password.clear();
      await widget.ready();
    } catch (cause) {
      if (mounted) setState(() => error = supportError(cause));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  void dispose() {
    username.dispose();
    password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(28),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Icon(
                  Icons.support_agent,
                  color: Color(0xffd89816),
                  size: 66,
                ),
                const SizedBox(height: 20),
                const Text(
                  '倾境客服',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 28, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 8),
                const Text(
                  '使用管理后台账号登录',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.grey),
                ),
                const SizedBox(height: 36),
                TextField(
                  controller: username,
                  enabled: !busy,
                  autofillHints: const [AutofillHints.username],
                  decoration: const InputDecoration(
                    labelText: '后台账号',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: password,
                  enabled: !busy,
                  obscureText: true,
                  autofillHints: const [AutofillHints.password],
                  onSubmitted: (_) => login(),
                  decoration: const InputDecoration(
                    labelText: '密码',
                    border: OutlineInputBorder(),
                  ),
                ),
                if (error.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 14),
                    child: Text(
                      error,
                      style: const TextStyle(color: Colors.deepOrange),
                    ),
                  ),
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: busy ? null : login,
                  child: Padding(
                    padding: const EdgeInsets.all(13),
                    child: Text(busy ? '登录中…' : '登录'),
                  ),
                ),
                const SizedBox(height: 24),
                const Text(
                  '保持应用在前台可及时收取消息\n后台或锁屏后，返回应用会补齐聊天记录',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.7,
                    color: Colors.grey,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
