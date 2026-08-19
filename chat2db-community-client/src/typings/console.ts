import { ConsoleStatus, DatabaseTypeCode, WorkspaceTabType, ConsoleOpenedStatus } from '@/constants';
import type { IConnectionEnv } from './connection';

export interface ICreateConsoleParams {
  name?: string;
  ddl?: string;
  dataSourceId: number;
  dataSourceName: string;
  environmentId?: number | null;
  environment?: IConnectionEnv | null;
  identityColor?: string | null;
  watermarkEnabled?: boolean | null;
  watermarkContent?: string | null;
  databaseType: DatabaseTypeCode;
  databaseName?: string;
  schemaName?: string;
  nameCustomized?: boolean;
  operationType?: WorkspaceTabType;
  loadSQL?: () => Promise<string>;
}

// Console details
export interface IConsole {
  id: number; // consoleId
  name: string; // console name
  ddl: string; // sql in console
  dataSourceId?: number; // Data source id
  dataSourceName?: string; // Data source name
  environmentId?: number | null;
  environment?: IConnectionEnv | null;
  identityColor?: string | null;
  watermarkEnabled?: boolean | null;
  watermarkContent?: string | null;
  type?: DatabaseTypeCode; // Database type
  databaseName?: string; // Database name
  schemaName?: string; // schema name
  nameCustomized?: boolean | null; // Whether the console tab name was manually customized
  status: ConsoleStatus; // Console status
  connectable: boolean; // Is it connectable?
  tabOpened?: ConsoleOpenedStatus; // Is the console tab open?
  operationType: WorkspaceTabType; // Operation type
  popoverContent?: string;
}

export type ICreateConsole = Omit<IConsole, 'id' | 'connectable'> & { id?: number };
