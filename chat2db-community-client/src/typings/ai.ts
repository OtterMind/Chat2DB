import { IDatabaseBaseInfo } from './database';
import { DatabaseTypeCode } from '@/constants';
import { PromptTableVO } from './chat';
import { ChatSourceType } from '@/constants/chat';

// Natural language to SQL
export interface TextToSQLParams {
  /**
   * Conversation ID, must be passed when opening in AI conversation page
   */
  chatId?: number;
  /**
   * Data source ID, must be passed when used in the console window
   */
  dataSourceId?: number;
  /**
   * Enter message
   */
  message: string;
  /**
   * Source
   */
  source: ChatSourceType;
  /**
   * List of table names
   */
  tableList?: PromptTableVO[];
}

// Natural language creation table
export interface TextToCreateTableParams {
  tableName: string;
  columnList: string;
  databaseType: DatabaseTypeCode;
}

// Natural language modification table
export interface TextToAlterTableParams extends IDatabaseBaseInfo {
  message: string;
}

// AI dialogue obtains input parameters of token
export type GetChatTokenParams = TextToSQLParams | TextToCreateTableParams | TextToAlterTableParams;

// AI dialogue obtains the return value of token
export interface GetChatTokenResponse {
  /**
   * Conversation ID
   */
  chatId: number;
  /**
   *IssueId
   */
  questionId: number;
  /**
   * token
   */
  token: string;
}

export interface IAIModel {
  /**
   * Model type
   */
  modelType: string;
  /**
   * Model name
   */
  modelName: string;
  /**
   * Model display name
   */
  displayName: string;
  /**
   * Whether it is the default model
   */
  isDefault: boolean;
}
