import { HTMLAttributes } from 'react';

export type NonNullable<T> = T extends null | undefined ? never : T;

export enum BooleanType {
  Yes = 'Y',
  No = 'N',
}

export interface IPageResponse<T> {
  data: T[];
  pageNo: number;
  pageSize: number;
  total: number;
  hasNextPage?: boolean;
}

export interface IPageParams {
  searchKey?: string;
  pageNo: number;
  pageSize: number;
  refresh?: boolean;
  hasNextPage?: boolean;
}

export interface IPagingData {
  searchKey?: string;
  hasNextPage?: boolean;
  pageNo: number;
  pageSize: number;
  total: number;
}

export interface Option {
  value: number | string;
  label: string;
  isLeaf?: boolean;
  children?: Option[];
}

export interface IUniversalTableParams {
  dataSourceId: string;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
}

/**
 * version return
 * VersionResponse
 */
export interface IVersionResponse {
  /**
   *Basic link
   */
  baseUrl?: string;
  /**
   * Download link
   */
  downloadLink?: string;
  /**
   * version
   */
  version?: string;
  /**
   * WeChat public account name
   */
  wechatMpName?: string;
}

export type DivProps = HTMLAttributes<HTMLDivElement>;
