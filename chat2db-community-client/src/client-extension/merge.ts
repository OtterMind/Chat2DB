import type { INavItem } from '@/typings/main';
import type { ClientNavigationContribution } from './types';

const assertUniqueContributions = <T extends { id: string }>(
  contributions: readonly T[],
  reservedIds: Iterable<string>,
): T[] => {
  const seenIds = new Set(reservedIds);

  return contributions.map((contribution) => {
    if (!contribution.id) {
      throw new Error('Client contribution id is required.');
    }
    if (seenIds.has(contribution.id)) {
      throw new Error(`Duplicate client contribution id: ${contribution.id}`);
    }
    seenIds.add(contribution.id);
    return contribution;
  });
};

export const mergeNavigationItems = (
  coreItems: readonly INavItem[],
  contributions: readonly ClientNavigationContribution[],
): INavItem[] => {
  const extensionItems = assertUniqueContributions(
    contributions,
    coreItems.map((item) => `${item.key}`),
  ).map(({ id, ...item }) => ({ ...item, key: id }));

  return [...coreItems, ...extensionItems];
};
