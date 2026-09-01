import createRequest from '../base';
import { IDBContextInfo } from '@/typings/database';

export type DropViewRequest = IDBContextInfo & { viewName: string };

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

export function buildDropViewRequest(request: DropViewRequest): DropViewRequest {
  return request;
}

const dropView = createRequest<DropViewRequest, any>('/api/rdb/view/drop', { method: 'post' });

export default {
  getViewMeta,
  createView,
  dropView,
};
