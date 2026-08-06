import type { ITableHeaderItem } from '@/typings/database';

export const getResultColumnTitle = (data: Pick<ITableHeaderItem, 'name' | 'comment'>): string => {
  let comment = data.comment?.trim() || '';
  if (comment.length > 10) {
    comment = `${comment.slice(0, 10)}...`;
  }
  return comment ? `${data.name}(${comment})` : data.name;
};
