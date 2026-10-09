import { apiRequest } from './apiClient';

export interface SecurityRule { key: string; title: string; description: string; enabled: boolean; available: boolean; platforms: string[]; threshold: number; windowSeconds: number; version: number }
export interface SecurityPolicy { enabled: boolean; version: number; rules: SecurityRule[] }
export interface SecurityBan { id: string; groupId: string; subjectType: 'DEVICE' | 'IP'; subjectValue: string; deviceId?: string; platform?: string; ip?: string; ruleKey: string; reason: string; bannedAt: string; bannedBy?: string; releasedAt?: string; releasedBy?: string; releaseReason?: string; version: number }
export interface BanPage { items: SecurityBan[]; total: number; page: number; pageSize: number }
export interface SecurityWhitelist { id: string; subjectType: 'DEVICE' | 'IP'; subjectValue: string; note: string; createdAt: string }
export interface SecurityEvent { id: string; action: string; actor?: string; result: string; changes: string; createdAt: string }

export const securityRepository = {
  async policy() { return (await apiRequest<SecurityPolicy>('/admin/security/policy')).data; },
  async savePolicy(policy: SecurityPolicy, enabled: boolean) { return (await apiRequest<SecurityPolicy>('/admin/security/policy', { method: 'PUT', csrf: true, body: JSON.stringify({ enabled, version: policy.version }) })).data; },
  async saveRule(rule: SecurityRule) { return (await apiRequest<SecurityRule>(`/admin/security/rules/${encodeURIComponent(rule.key)}`, { method: 'PUT', csrf: true, body: JSON.stringify({ enabled: rule.enabled, threshold: rule.threshold, windowSeconds: rule.windowSeconds, version: rule.version }) })).data; },
  async bans(search: string, status: string, page: number) { return (await apiRequest<BanPage>(`/admin/security/bans?${new URLSearchParams({ search, status, page: String(page), pageSize: '50' })}`)).data; },
  async ban(deviceId: string, ip: string, reason: string) { await apiRequest('/admin/security/bans', { method: 'POST', csrf: true, body: JSON.stringify({ deviceId: deviceId.trim() || null, ip: ip.trim() || null, reason: reason.trim() }) }); },
  async release(ban: SecurityBan, reason: string, includeRelated: boolean) { await apiRequest(`/admin/security/bans/${ban.id}/release`, { method: 'POST', csrf: true, body: JSON.stringify({ version: ban.version, reason: reason.trim(), includeRelated }) }); },
  async whitelist() { return (await apiRequest<SecurityWhitelist[]>('/admin/security/whitelist')).data; },
  async addWhitelist(subjectType: string, subjectValue: string, note: string) { await apiRequest('/admin/security/whitelist', { method: 'POST', csrf: true, body: JSON.stringify({ subjectType, subjectValue: subjectValue.trim(), note: note.trim() }) }); },
  async removeWhitelist(id: string) { await apiRequest(`/admin/security/whitelist/${id}`, { method: 'DELETE', csrf: true }); },
  async events() { return (await apiRequest<SecurityEvent[]>('/admin/security/events')).data; }
};

export const securityTime = (value?: string) => value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value)) : '—';
export function securityEventText(event: SecurityEvent): string {
  try {
    const c = JSON.parse(event.changes) as Record<string, unknown>;
    if (c.rule) return `${String(c.rule)}：${c.enabled ? '开启' : '关闭'}，阈值 ${String(c.threshold)} / ${String(c.windowSeconds)} 秒`;
    if (c.banId) return `解除记录 ${String(c.banId)}${c.includeRelated ? '及关联封禁' : ''}：${String(c.reason)}`;
    if (c.reason) return `永久封禁 ${[c.deviceId && `设备 ${String(c.deviceId)}`, c.ip].filter(Boolean).join('、')}：${String(c.reason)}`;
    if (c.subjectType) return `添加${c.subjectType === 'DEVICE' ? '设备' : 'IP'}白名单 ${String(c.subjectValue)}：${String(c.note)}`;
    if (c.whitelistId) return `移除白名单记录 ${String(c.whitelistId)}`;
    if (typeof c.enabled === 'boolean') return c.enabled ? '开启自动风控' : '关闭自动风控，历史封禁继续生效';
  } catch { /* Old audit entries may not contain a JSON summary. */ }
  return '安全风控操作';
}
