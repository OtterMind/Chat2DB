import type { SettingMenuItem } from '@/blocks/Setting/SettingLayout';
import type { LangType } from '@/constants/settings';
import type { INavItem } from '@/typings/main';
import type { ReactNode } from 'react';
import type { ClientWorkspaceContext } from '@/client-context/types';

export interface ClientSettingContext {
  language: LangType;
}

export type ClientNavigationContribution = Omit<INavItem, 'key'> & {
  /** Stable identifier used as both the contribution identity and navigation key. */
  id: string;
};

export interface ClientNavigationResolutionContext {
  requestedPage: string;
  allItems: readonly INavItem[];
  visibleItems: readonly INavItem[];
}

export type ClientMainPageCoreAction = 'settings';

export interface ClientMainPageSlots {
  actionBarBeforeTerminal?: ReactNode;
  actionBarAfterTerminal?: ReactNode;
  /** @deprecated Use actionBarBeforeTerminal. */
  actionBarFooter?: ReactNode;
  titleBarActions?: ReactNode;
}

export interface ClientMainPageExtension {
  /** React hook used by a product layer to derive its visible navigation items. */
  useNavigationItems: (items: readonly INavItem[]) => readonly INavItem[];
  resolveNavigationPage?: (context: ClientNavigationResolutionContext) => string;
  slots?: ClientMainPageSlots;
  hiddenCoreActions?: readonly ClientMainPageCoreAction[];
}

export type ResourceOperation =
  | 'SELECT'
  | 'INSERT'
  | 'UPDATE'
  | 'DELETE'
  | 'CREATE'
  | 'ALTER'
  | 'DROP'
  | 'TRUNCATE';

export interface ResourceOperationRequest {
  dataSourceId: number;
  dbType?: string;
  databaseName?: string;
  schemaName?: string;
  tableName?: string;
  operationTypes: readonly ResourceOperation[];
}

export type ResourceOperationCapabilities = Readonly<Record<ResourceOperation, boolean>>;

export interface KnowledgeMentionCandidate {
  id: number;
  type: 'KNOWLEDGE_TERM' | 'BUSINESS_LOGIC' | 'SQL_TEMPLATE';
  key: string;
  value: string;
}

export interface KnowledgeMentionRequest {
  searchKey?: string;
  inputText?: string;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  pageNo?: number;
  pageSize?: number;
}

export interface KnowledgeMentionPage {
  data: readonly KnowledgeMentionCandidate[];
  pageNo: number;
  pageSize: number;
  total: number;
  hasNextPage: boolean;
}

export interface TableMetadataSearchRequest {
  dataSourceId: number;
  searchKey: string;
  limit?: number;
}

export interface TableMetadataSearchResult {
  databaseName?: string;
  schemaName?: string;
  name: string;
  comment?: string;
}

export type PermissionDeniedInteraction = 'prompt-application' | 'notify-only';

export interface ClientRequestPolicy {
  permissionDeniedInteraction: PermissionDeniedInteraction;
}

export interface PermissionApplicationRequest {
  applyType: 'data';
  dataSourceId: number;
  databaseName?: string;
  dataSourceName?: string;
  schemaName?: string;
}

export interface ConnectionStoragePolicy {
  value: string;
  disabled: boolean;
}

export interface DashboardActionContext {
  dashboardId: number | string;
}

export interface ClientExtension {
  globalComponents?: ReactNode;
  mainPage: ClientMainPageExtension;
  settings?: {
    items?: (context: ClientSettingContext) => readonly SettingMenuItem[];
    about?: ReactNode;
  };
  navigationItems?: readonly ClientNavigationContribution[];
  resourceOperations?: (
    request: ResourceOperationRequest,
  ) => Promise<ResourceOperationCapabilities>;
  knowledgeMentions?: (request: KnowledgeMentionRequest) => Promise<KnowledgeMentionPage>;
  tableMetadataSearch?: (request: TableMetadataSearchRequest) => Promise<readonly TableMetadataSearchResult[]>;
  dashboardActions?: (context: DashboardActionContext) => ReactNode;
  openPermissionApplication?: (request: PermissionApplicationRequest) => void;
  connectionStoragePolicy?: (workspace: ClientWorkspaceContext | null) => ConnectionStoragePolicy | undefined;
  reportClient?: (request: { deviceUuid: string; clientVersion: string; userAgent: string }) => Promise<void>;
  requestPolicy: ClientRequestPolicy;
}
