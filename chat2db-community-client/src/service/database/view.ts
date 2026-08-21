import createRequest from '../base';
import { IDBContextInfo } from '@/typings/database';

const getViewMeta = createRequest<IDBContextInfo & { viewName: string }, any>('/api/rdb/view/view_meta', {});

const createView = createRequest<IDBContextInfo & {
  viewName: string;
  viewBody: string;
  algorithm?: string;
  definer?: string;
  security?: string;
  checkOption?: string;
  useOrReplace?: boolean;
}, any>('/api/rdb/view/create', { method: 'post' });

const dropView = createRequest<IDBContextInfo & { tableName: string }, any>('/api/rdb/view/drop', { method: 'post' });

export default {
  getViewMeta,
  createView,
  dropView,
};
