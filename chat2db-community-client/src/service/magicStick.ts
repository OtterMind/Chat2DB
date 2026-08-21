import {
  GetChatTokenResponse,
  TextToAlterTableParams,
  TextToCreateTableParams,
  TextToSQLParams,
} from '@/typings/ai';
import createRequest from './base';
import { ChatSourceType } from '@/constants/chat';
import { DatabaseTypeCode } from '@/constants';
import { IEditTableInfo } from '@/typings';

const prefix = '/api/ai/slash_magic';

/** Convert natural language to SQL */
const text2SQL = createRequest<TextToSQLParams, GetChatTokenResponse>(`${prefix}/sql_generate`, {
  method: 'post',
  errorLevel: false,
});

/** Generate qualified database.table names; the backend resolves databaseSourceId from the dataset ID. */
const sqlGenerateNLP = createRequest<TextToSQLParams, GetChatTokenResponse>(`${prefix}/sql_generate_nlp`, {
  method: 'post',
  errorLevel: false,
});

/** Explain SQL */
const explainSQL = createRequest<TextToSQLParams, GetChatTokenResponse>(`${prefix}/sql_explain`, {
  method: 'post',
  errorLevel: false,
});

/** Optimize SQL. */
const optimizeSQL = createRequest<TextToSQLParams, GetChatTokenResponse>(`${prefix}/sql_optimise`, {
  method: 'post',
  errorLevel: false,
});

/** SQL conversion */
const convertSQL = createRequest<TextToSQLParams & { targetSqlType: string }, GetChatTokenResponse>(
  `${prefix}/sql_convert`,
  {
    method: 'post',
    errorLevel: false,
  },
);

/** CRUD generation */
const generateCRUD = createRequest<TextToSQLParams, GetChatTokenResponse>(`${prefix}/crud_generate`, {
  method: 'post',
  errorLevel: false,
});

// SQL prompt
const queryPrompt = createRequest<
  {
    dataSourceId?: number;
    databaseName?: string;
    schemaName?: string;
    source: ChatSourceType.DATASOURCE_CHAT;
    beforeContext: string;
    afterContext: string;
  },
  {
    content: string;
    selectPrompt: {
      /** What type is in front? */
      type: string;
      value: string;
      alias: string;
      items: Array<{ type: string; value: string }>;
    };
  }
>(`${prefix}/sql_prompt2`, {
  method: 'post',
  errorLevel: false,
  permissionError: false,
});

// Parse SQL.
const parseSQL = createRequest<
  { sql: string; dataSourceId: number; databaseName?: string; schemaName?: string },
  GetChatTokenResponse
>(`${prefix}/sql_parse`, {
  method: 'post',
});

const textToCreateTable = createRequest<
  {
    tableName: string;
    columnList: string;
    databaseType: DatabaseTypeCode;
  },
  IEditTableInfo
>(`${prefix}/text_to_create_table_stream`, { method: 'post' });

const textToCreateColumn = createRequest<TextToCreateTableParams, GetChatTokenResponse>(
  `${prefix}/text_to_create_column`,
  {
    method: 'post',
  },
);

const textToAlterTable = createRequest<TextToAlterTableParams, GetChatTokenResponse>(`${prefix}/text_to_alter_column`, {
  method: 'post',
});

// Multi-operation streaming interface
const streamQA = createRequest<TextToSQLParams, GetChatTokenResponse>(`/stream_qa`, {
  method: 'post',
  errorLevel: false,
});

// Two non-streaming interfaces
/** Generate Excel questions and answers */
const excelChat = createRequest<
  TextToSQLParams,
  GetChatTokenResponse & {
    excelSchemas: string;
  }
>(`${prefix}/excel_qa`, {
  method: 'post',
});

/** Generate reports */
const text2Chart = createRequest<TextToSQLParams, GetChatTokenResponse & { chartSchema: string }>(
  `/api/v1/ai/slash_magic/dashboard_generate`,
  {
    method: 'post',
    errorLevel: false,
  },
);

export default {
  text2SQL,
  sqlGenerateNLP,
  explainSQL,
  optimizeSQL,
  convertSQL,
  generateCRUD,
  queryPrompt,
  parseSQL,
  textToCreateTable,
  textToCreateColumn,
  textToAlterTable,
  text2Chart,
  excelChat,
  streamQA,
};
