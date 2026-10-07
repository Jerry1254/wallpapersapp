export const currentResourceVersion = <T extends { versionNo: number; status: string }>(
  versions: T[], preferPublished: boolean
): T | undefined => {
  const ordered = [...versions].sort((a, b) => b.versionNo - a.versionNo);
  const statuses = preferPublished ? ['PUBLISHED', 'READY'] : ['READY', 'PUBLISHED'];
  for (const status of statuses) {
    const version = ordered.find((item) => item.status === status);
    if (version) return version;
  }
  return undefined;
};
