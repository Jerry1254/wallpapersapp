import { afterEach, expect, it, vi } from 'vitest';
import { setCsrfToken } from './apiClient';
import { legalDocumentRepository, type AdminLegalDocument } from './legalDocumentRepository';
afterEach(() => vi.unstubAllGlobals());
const content = { title: '隐私政策', effectiveDate: '2026年10月9日', introduction: '说明', sections: [{ title: '重要提示', body: '正文' }] };
const document: AdminLegalDocument = { key: 'privacy', version: 2, draft: content, requiresReconsent: true,
  published: { key: 'privacy', revision: 1, consentRevision: 1, content, publishedAt: '2026-10-09T00:00:00Z' } };
it('saves drafts and publishes with server returned version, authenticated CSRF requests', async () => {
  setCsrfToken('csrf-test');
  const fetchMock = vi.fn().mockImplementation(async (url: string) => new Response(JSON.stringify(
    url.endsWith('/legal-documents') ? { items: [document] } : { ...document, version: 3 }
  ), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetchMock);
  expect(await legalDocumentRepository.list()).toEqual([document]);
  const saved = await legalDocumentRepository.save(document);
  await legalDocumentRepository.publish(saved);
  const draftOptions = fetchMock.mock.calls[1]![1] as RequestInit;
  expect(new Headers(draftOptions.headers).get('If-Match')).toBe('"2"');
  expect(new Headers(draftOptions.headers).get('X-CSRF-Token')).toBe('csrf-test');
  expect(JSON.parse(String(draftOptions.body))).toEqual({ content, requiresReconsent: true });
  const publishOptions = fetchMock.mock.calls[2]![1] as RequestInit;
  expect(new Headers(publishOptions.headers).get('If-Match')).toBe('"3"');
  expect(publishOptions.method).toBe('POST');
});
