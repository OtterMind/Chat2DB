import { ConsoleStatus, DatabaseTypeCode, WorkspaceTabType, ConsoleOpenedStatus } from '@/constants';

export interface ICreateConsoleParams {
  name?: string;
  ddl?: string;
  dataSourceId: number;
  dataSourceName: string;
  databaseType: DatabaseTypeCode;
  databaseName?: string;
  schemaName?: string;
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
  type?: DatabaseTypeCode; // Database type
  databaseName?: string; // Database name
  schemaName?: string; // schema name
  status: ConsoleStatus; // Console status
  connectable: boolean; // Is it connectable?
  tabOpened?: ConsoleOpenedStatus; // Is the console tab open?
  operationType: WorkspaceTabType; // Operation type
  popoverContent?: string;
}

export type ICreateConsole = Omit<IConsole, 'id' | 'connectable'> & { id?: number };
