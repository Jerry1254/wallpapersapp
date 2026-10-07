import { describe, expect, it } from 'vitest';
import { currentResourceVersion } from './resourceVersion';

describe('current resource version', () => {
  const versions = [
    { id: 'failed-history', versionNo: 3, status: 'READY' },
    { id: 'published', versionNo: 2, status: 'PUBLISHED' },
    { id: 'retired', versionNo: 1, status: 'RETIRED' }
  ];
  it('keeps published/offline products on their published resource', () => {
    expect(currentResourceVersion(versions, true)?.id).toBe('published');
  });
  it('uses the latest prepared version for draft products without mutating input', () => {
    const original = [...versions];
    expect(currentResourceVersion(versions, false)?.id).toBe('failed-history');
    expect(versions).toEqual(original);
  });
  it('allows new capabilities with no published resource and rejects retired-only history', () => {
    expect(currentResourceVersion([versions[0]], true)?.id).toBe('failed-history');
    expect(currentResourceVersion([versions[2]], true)).toBeUndefined();
  });
});
