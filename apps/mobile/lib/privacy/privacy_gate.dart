import 'package:flutter/material.dart';
import '../config/app_branding.dart';
import 'package:flutter/services.dart';
import '../design_system/qj_components.dart';
import '../design_system/qj_theme.dart';
import 'policies.dart';
import 'remote_policies.dart';

abstract interface class PrivacyConsentStore {
  Future<String?> acceptedVersion();
  Future<void> accept(String version);
}

class PlatformPrivacyConsentStore implements PrivacyConsentStore {
  const PlatformPrivacyConsentStore();

  static const _channel = MethodChannel('qingjing/privacy_consent');

  @override
  Future<String?> acceptedVersion() =>
      _channel.invokeMethod<String>('acceptedVersion');

  @override
  Future<void> accept(String version) =>
      _channel.invokeMethod<void>('acceptVersion', version);
}

class MemoryPrivacyConsentStore implements PrivacyConsentStore {
  MemoryPrivacyConsentStore({String? accepted}) : _accepted = accepted;

  String? _accepted;

  @override
  Future<String?> acceptedVersion() async => _accepted;

  @override
  Future<void> accept(String version) async => _accepted = version;
}

class PrivacyGate extends StatefulWidget {
  const PrivacyGate({
    super.key,
    required this.store,
    required this.builder,
    this.onReject,
    this.policySource,
  });

  final PrivacyConsentStore store;
  final WidgetBuilder builder;
  final VoidCallback? onReject;
  final PolicySource? policySource;

  @override
  State<PrivacyGate> createState() => _PrivacyGateState();
}

class _PrivacyGateState extends State<PrivacyGate> {
  bool checking = true;
  bool accepted = false;
  bool saving = false;
  String? error;
  PublishedPolicies policies = PublishedPolicies.bundled;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final current =
          await widget.policySource?.load() ?? PublishedPolicies.bundled;
      final version = await widget.store.acceptedVersion();
      if (!mounted) return;
      setState(() {
        policies = current;
        accepted = version == current.consentVersion;
        checking = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        checking = false;
        error = '无法读取本地同意记录，请重试';
      });
    }
  }

  Future<void> _accept() async {
    if (saving) return;
    setState(() {
      saving = true;
      error = null;
    });
    try {
      await widget.store.accept(policies.consentVersion);
      if (!mounted) return;
      setState(() {
        accepted = true;
        saving = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        saving = false;
        error = '暂时无法保存选择，请重试';
      });
    }
  }

  void _reject() {
    final callback = widget.onReject;
    if (callback != null) {
      callback();
      return;
    }
    SystemNavigator.pop(animated: true);
  }

  @override
  Widget build(BuildContext context) {
    if (checking) return const _PrivacyLoading();
    return PolicyScope(
      policies: policies,
      child: Builder(
        builder: (context) {
          if (accepted) return widget.builder(context);
          return _ConsentScreen(
            saving: saving,
            error: error,
            onAccept: _accept,
            onReject: _reject,
            onlinePolicies: widget.policySource != null,
          );
        },
      ),
    );
  }
}

class _PrivacyLoading extends StatelessWidget {
  const _PrivacyLoading();

  @override
  Widget build(BuildContext context) => const Scaffold(
    body: SafeArea(child: Center(child: CircularProgressIndicator())),
  );
}

class _ConsentScreen extends StatelessWidget {
  const _ConsentScreen({
    required this.saving,
    required this.error,
    required this.onAccept,
    required this.onReject,
    required this.onlinePolicies,
  });

  final bool saving;
  final String? error;
  final VoidCallback onAccept, onReject;
  final bool onlinePolicies;

  void _open(BuildContext context, PolicyDocument document) {
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => PolicyDocumentScreen(
          document: PolicyScope.of(context).document(document),
          effectiveDate: PolicyScope.of(context).date(document),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: T.colorSurfaceMuted,
    body: SafeArea(
      child: Stack(
        children: [
          Positioned(
            left: T.space5,
            top: T.space6,
            child: Text(
              AppBrandingScope.of(context).shortName,
              style: const TextStyle(
                fontSize: 31,
                fontWeight: FontWeight.w900,
                color: T.colorInk,
              ),
            ),
          ),
          Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(T.space5),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 390),
                child: Material(
                  color: T.colorSurface,
                  borderRadius: BorderRadius.circular(T.radiusCard),
                  elevation: 10,
                  shadowColor: Colors.black12,
                  child: Padding(
                    padding: const EdgeInsets.all(T.space5),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '隐私政策与用户协议',
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                        const SizedBox(height: T.space3),
                        Text(
                          '欢迎使用${AppBrandingScope.of(context).serviceName}。请您在使用前阅读并同意以下协议。',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                        const SizedBox(height: T.space3),
                        Text(
                          onlinePolicies
                              ? PolicyScope.of(context).privacy.introduction
                              : '为了识别当前安装、保护兑换权益并提供下载服务，您同意后 App 将生成匿名安装凭据，并处理平台类型、App 生成的公钥或凭据、兑换、下载及安全记录。我们不读取通讯录、短信、通话记录、位置、摄像头、麦克风或相册内容。',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        const SizedBox(height: T.space2),
                        Wrap(
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: [
                            const Text('点击同意即表示您已阅读并同意'),
                            TextButton(
                              onPressed: () => _open(context, privacyPolicy),
                              child: const Text('《隐私政策》'),
                            ),
                            const Text('和'),
                            TextButton(
                              onPressed: () => _open(context, userAgreement),
                              child: const Text('《用户协议》'),
                            ),
                          ],
                        ),
                        if (error != null) ...[
                          const SizedBox(height: T.space2),
                          Text(
                            error!,
                            style: const TextStyle(color: T.colorDanger),
                          ),
                        ],
                        const SizedBox(height: T.space4),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton(
                            onPressed: saving ? null : onAccept,
                            child: Text(saving ? '正在保存' : '同意并继续'),
                          ),
                        ),
                        const SizedBox(height: T.space2),
                        SizedBox(
                          width: double.infinity,
                          child: OutlinedButton(
                            onPressed: saving ? null : onReject,
                            child: const Text('不同意并退出'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

class PolicyCenterScreen extends StatelessWidget {
  const PolicyCenterScreen({
    super.key,
    this.policies = PublishedPolicies.bundled,
  });

  final PublishedPolicies policies;

  @override
  Widget build(BuildContext context) => PolicyScope(
    policies: policies,
    child: Builder(
      builder: (context) => Scaffold(
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
              child: ListView(
                padding: const EdgeInsets.fromLTRB(
                  T.space5,
                  0,
                  T.space5,
                  T.space8,
                ),
                children: [
                  QjPageHeader(title: '协议与隐私'),
                  const SizedBox(height: T.space5),
                  _PolicyLink(document: privacyPolicy),
                  const SizedBox(height: T.space3),
                  _PolicyLink(document: userAgreement),
                  const SizedBox(height: T.space4),
                  Text(
                    '联系邮箱：$qingjingSupportEmail',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _PolicyLink extends StatelessWidget {
  const _PolicyLink({required this.document});

  final PolicyDocument document;

  @override
  Widget build(BuildContext context) => QjSurface(
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(
        horizontal: T.space4,
        vertical: T.space2,
      ),
      title: Text(PolicyScope.of(context).document(document).title),
      subtitle: Text('生效日期：${PolicyScope.of(context).date(document)}'),
      trailing: const QjIcon('chevron-right'),
      onTap: () => Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => PolicyDocumentScreen(
            document: PolicyScope.of(context).document(document),
            effectiveDate: PolicyScope.of(context).date(document),
          ),
        ),
      ),
    ),
  );
}

class PolicyDocumentScreen extends StatelessWidget {
  const PolicyDocumentScreen({
    super.key,
    required this.document,
    this.effectiveDate = policyEffectiveDate,
  });

  final PolicyDocument document;
  final String effectiveDate;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: T.sizeContentMax),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(T.space5, 0, T.space5, T.space8),
            children: [
              QjPageHeader(title: document.title),
              const SizedBox(height: T.space4),
              Text(
                '生效日期：$effectiveDate',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: T.space3),
              Text(
                AppBrandingScope.of(context).productText(document.introduction),
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              for (final section in document.sections) ...[
                const SizedBox(height: T.space5),
                Text(
                  section.title,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: T.space2),
                Text(
                  AppBrandingScope.of(context).productText(section.body),
                  style: Theme.of(context).textTheme.bodyMedium,
                ),
              ],
            ],
          ),
        ),
      ),
    ),
  );
}
