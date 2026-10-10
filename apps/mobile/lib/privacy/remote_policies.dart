import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'policies.dart';

class PublishedPolicies {
  const PublishedPolicies({
    required this.consentVersion,
    required this.privacy,
    required this.terms,
    required this.privacyDate,
    required this.termsDate,
  });
  static const bundled = PublishedPolicies(
    consentVersion: policyVersion,
    privacy: privacyPolicy,
    terms: userAgreement,
    privacyDate: policyEffectiveDate,
    termsDate: policyEffectiveDate,
  );
  final String consentVersion, privacyDate, termsDate;
  final PolicyDocument privacy, terms;
  PolicyDocument document(PolicyDocument original) =>
      original == userAgreement ? terms : privacy;
  String date(PolicyDocument original) =>
      original == userAgreement ? termsDate : privacyDate;

  static PublishedPolicies parse(String text) {
    if (text.length > 500000) throw const FormatException('协议响应过长');
    final root = jsonDecode(text) as Map<String, dynamic>;
    final items = root['items'] as List<dynamic>;
    if (items.length != 2) throw const FormatException('协议不完整');
    Map<String, dynamic> item(String key) =>
        items.cast<Map<String, dynamic>>().singleWhere((d) => d['key'] == key);
    String requiredText(dynamic value, int max) {
      if (value is! String || value.trim().isEmpty || value.length > max) {
        throw const FormatException('协议内容无效');
      }
      return value;
    }

    final privacyItem = item('privacy'), termsItem = item('terms');
    final version =
        'privacy:${privacyItem['consentRevision']}|terms:${termsItem['consentRevision']}';
    for (final i in [privacyItem, termsItem]) {
      if (i['revision'] is! int ||
          i['revision'] < 1 ||
          i['consentRevision'] is! int ||
          i['consentRevision'] < 1) {
        throw const FormatException('协议版本无效');
      }
    }
    if (root['consentVersion'] != version) {
      throw const FormatException('协议版本不一致');
    }
    PolicyDocument doc(Map<String, dynamic> item) {
      final c = item['content'] as Map<String, dynamic>;
      final sections = c['sections'] as List<dynamic>;
      if (sections.isEmpty || sections.length > 60) {
        throw const FormatException('协议章节无效');
      }
      return PolicyDocument(
        title: requiredText(c['title'], 100),
        introduction: requiredText(c['introduction'], 10000),
        sections: sections
            .map(
              (s) => PolicySection(
                requiredText(s['title'], 200),
                requiredText(s['body'], 20000),
              ),
            )
            .toList(),
      );
    }

    return PublishedPolicies(
      // The installed app's new processing must be disclosed even when the server
      // or a valid same-origin cache still returns an older published policy.
      consentVersion: '$version|app-privacy:$policyVersion',
      privacy: withCurrentAppDisclosure(doc(privacyItem)),
      terms: doc(termsItem),
      privacyDate:
          '${requiredText(privacyItem['content']['effectiveDate'], 40)}（本版本补充：$policyEffectiveDate）',
      termsDate: requiredText(termsItem['content']['effectiveDate'], 40),
    );
  }

  static PolicyDocument withCurrentAppDisclosure(PolicyDocument document) =>
      PolicyDocument(
        title: document.title,
        introduction: document.introduction,
        sections: [
          ...document.sections.where(
            (section) => section.title != currentAppPrivacySupplement.title,
          ),
          currentAppPrivacySupplement,
        ],
      );
}

abstract interface class PolicySource {
  Future<PublishedPolicies> load();
}

class BundledPolicySource implements PolicySource {
  const BundledPolicySource();
  @override
  Future<PublishedPolicies> load() async => PublishedPolicies.bundled;
}

class RemotePolicySource implements PolicySource {
  RemotePolicySource(this.apiBase, {HttpClient Function()? createClient})
    : _createClient = createClient ?? HttpClient.new;
  final HttpClient Function() _createClient;
  final Uri apiBase;
  static const _channel = MethodChannel('qingjing/privacy_consent');
  @override
  Future<PublishedPolicies> load() async {
    PublishedPolicies fallback = PublishedPolicies.bundled;
    try {
      final cache = await _channel.invokeMethod<String>('cachedPolicies');
      if (cache != null) {
        final wrapper = jsonDecode(cache) as Map<String, dynamic>;
        if (wrapper['apiBase'] == apiBase.toString()) {
          fallback = PublishedPolicies.parse(wrapper['body'] as String);
        }
      }
    } catch (_) {
      /* A missing or invalid cache cannot grant consent. */
    }
    final client = _createClient()
      ..connectionTimeout = const Duration(seconds: 5);
    try {
      final text = await (() async {
        final request = await client.getUrl(
          Uri.parse(
            '${apiBase.toString().replaceFirst(RegExp(r'/$'), '')}/public/legal-documents',
          ),
        );
        // No installation ID, session token, cookies, app-version or analytics headers.
        request.followRedirects = false;
        request.headers.set(HttpHeaders.acceptHeader, 'application/json');
        final response = await request.close();
        if (response.statusCode != 200) throw const HttpException('协议暂不可用');
        final bytes = <int>[];
        await for (final chunk in response) {
          bytes.addAll(chunk);
          if (bytes.length > 1000000) throw const FormatException('协议响应过长');
        }
        return utf8.decode(bytes);
      })().timeout(const Duration(seconds: 5));
      final current = PublishedPolicies.parse(text);
      try {
        await _channel.invokeMethod<void>(
          'savePolicies',
          jsonEncode({'apiBase': apiBase.toString(), 'body': text}),
        );
      } catch (_) {
        /* Published content is still usable if caching is unavailable. */
      }
      return current;
    } catch (_) {
      return fallback;
    } finally {
      client.close(force: true);
    }
  }
}

class PolicyScope extends InheritedWidget {
  const PolicyScope({
    super.key,
    required this.policies,
    this.source,
    required super.child,
  });
  final PublishedPolicies policies;
  final PolicySource? source;
  static PublishedPolicies of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<PolicyScope>()?.policies ??
      PublishedPolicies.bundled;
  static PolicySource? sourceOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<PolicyScope>()?.source;
  @override
  bool updateShouldNotify(PolicyScope oldWidget) =>
      policies != oldWidget.policies || source != oldWidget.source;
}
