import { apiRequest } from './apiClient';

export interface LegalSection { title: string; body: string }
export interface LegalContent { title: string; effectiveDate: string; introduction: string; sections: LegalSection[] }
export interface PublishedLegalDocument { key: 'privacy' | 'terms'; revision: number; consentRevision: number; content: LegalContent; publishedAt: string }
export interface AdminLegalDocument { key: 'privacy' | 'terms'; version: number; draft: LegalContent; requiresReconsent: boolean; published: PublishedLegalDocument }
const root = '/admin/legal-documents';
export const legalDocumentRepository = {
  async list(): Promise<AdminLegalDocument[]> {
    return (await apiRequest<{ items: AdminLegalDocument[] }>(root)).data.items;
  },
  async save(document: AdminLegalDocument): Promise<AdminLegalDocument> {
    return (await apiRequest<AdminLegalDocument>(`${root}/${document.key}`, {
      method: 'PUT', csrf: true, headers: { 'If-Match': `"${document.version}"` },
      body: JSON.stringify({ content: document.draft, requiresReconsent: document.requiresReconsent })
    })).data;
  },
  async publish(document: AdminLegalDocument): Promise<AdminLegalDocument> {
    return (await apiRequest<AdminLegalDocument>(`${root}/${document.key}/publish`, {
      method: 'POST', csrf: true, headers: { 'If-Match': `"${document.version}"` }
    })).data;
  }
};
