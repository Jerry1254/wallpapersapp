import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { http } from '@kit.NetworkKit';
import { PolicyDocument, POLICY_VERSION, POLICY_EFFECTIVE_DATE, PRIVACY_POLICY, USER_AGREEMENT } from './PolicyDocuments';

interface LegalContent extends PolicyDocument { effectiveDate: string; }
interface PublishedDocument { key: string; revision: number; consentRevision: number; content: LegalContent; }
interface PublishedManifest { consentVersion: string; items: PublishedDocument[]; }
interface PolicyCache { apiBase: string; body: string; }
export class PublishedPolicies {
  consentVersion: string = POLICY_VERSION;
  privacy: PolicyDocument = PRIVACY_POLICY;
  terms: PolicyDocument = USER_AGREEMENT;
  privacyDate: string = POLICY_EFFECTIVE_DATE;
  termsDate: string = POLICY_EFFECTIVE_DATE;

  static parse(text: string): PublishedPolicies {
    if (text.length > 500000) throw new Error('Invalid legal response');
    const manifest: PublishedManifest = JSON.parse(text) as PublishedManifest;
    if (!Array.isArray(manifest.items) || manifest.items.length !== 2) throw new Error('Missing legal documents');
    const privacy: PublishedDocument | undefined = manifest.items.find((item: PublishedDocument) => item.key === 'privacy');
    const terms: PublishedDocument | undefined = manifest.items.find((item: PublishedDocument) => item.key === 'terms');
    if (privacy === undefined || terms === undefined) throw new Error('Missing legal documents');
    for (const item of [privacy, terms]) {
      if (!Number.isInteger(item.revision) || item.revision < 1 ||
        !Number.isInteger(item.consentRevision) || item.consentRevision < 1) throw new Error('Invalid legal revision');
      PublishedPolicies.validateText(item.content.title, 100);
      PublishedPolicies.validateText(item.content.effectiveDate, 40);
      PublishedPolicies.validateText(item.content.introduction, 10000);
      if (!Array.isArray(item.content.sections) || item.content.sections.length < 1 || item.content.sections.length > 60)
        throw new Error('Invalid legal sections');
      for (const section of item.content.sections) {
        PublishedPolicies.validateText(section.title, 200);
        PublishedPolicies.validateText(section.body, 20000);
      }
    }
    const version: string = 'privacy:' + privacy.consentRevision + '|terms:' + terms.consentRevision;
    if (manifest.consentVersion !== version) throw new Error('Inconsistent legal revision');
    const result: PublishedPolicies = new PublishedPolicies();
    result.consentVersion = version;
    result.privacy = privacy.content;
    result.terms = terms.content;
    result.privacyDate = privacy.content.effectiveDate;
    result.termsDate = terms.content.effectiveDate;
    return result;
  }
  private static validateText(value: string, max: number): void {
    if (typeof value !== 'string' || value.trim().length === 0 || value.length > max) throw new Error('Invalid legal text');
  }
}

export class RemotePolicies {
  static async load(context: common.UIAbilityContext, apiBase: string): Promise<PublishedPolicies> {
    let fallback: PublishedPolicies = new PublishedPolicies();
    let store: preferences.Preferences | null = null;
    try {
      store = await preferences.getPreferences(context, 'qingjing_privacy_state');
      const cached: string = String(await store.get('cached_online_policies', ''));
      if (cached.length > 0) {
        const wrapper: PolicyCache = JSON.parse(cached) as PolicyCache;
        if (wrapper.apiBase === apiBase) fallback = PublishedPolicies.parse(wrapper.body);
      }
    } catch (error) { /* Invalid caches cannot grant consent. */ }
    const request: http.HttpRequest = http.createHttp();
    try {
      // Anonymous request only; no installation credentials or business headers.
      const response: http.HttpResponse = await request.request(apiBase + '/public/legal-documents', {
        method: http.RequestMethod.GET, header: { 'Accept': 'application/json' },
        connectTimeout: 5000, readTimeout: 5000, usingCache: false, maxLimit: 1000000,
        expectDataType: http.HttpDataType.STRING
      });
      if (response.responseCode !== 200 || typeof response.result !== 'string') throw new Error('Legal documents unavailable');
      const body: string = response.result as string;
      const current: PublishedPolicies = PublishedPolicies.parse(body);
      try {
        if (store !== null) {
          const wrapper: PolicyCache = { apiBase: apiBase, body: body };
          await store.put('cached_online_policies', JSON.stringify(wrapper));
          await store.flush();
        }
      } catch (error) { /* Published content remains usable without a cache. */ }
      return current;
    } catch (error) { return fallback; }
    finally { request.destroy(); }
  }
}
