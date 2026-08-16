import { QuestionType, AnswerPartsType, ChatSourceType, AnswerPartsStatus } from '@/constants/chat';
import { IDatabaseBaseInfo, IManageResultData } from '@/typings/database';
import { ChartSchema } from '@/blocks/BI/Chart/typings';
import { TaskStatus } from '@/constants';

/**
 * Dashboard
 */
export interface DashboardVO {
  /**
   * Chart ID list
   */
  chartIds?: number[];
  /**
   * Report description
   */
  description?: string;
  /**
   * Creation time
   */
  gmtCreate?: number;
  /**
   * Modification time
   */
  gmtModified?: number;
  /**
   * Primary key
   */
  id?: number;
  /**
   * Report name
   */
  name?: string;
  /**
   *Organization id
   */
  organizationId?: number;
  /**
   * Report layout information
   */
  schema?: string;
  [property: string]: any;
}

/**
 * Question VO
 */
export interface QuestionVO {
  /**
   * Question id
   */
  id?: number;
  /**
   * chat id
   */
  chatId?: number;
  /**
   * Question
   */
  content: string;
  /**
   * Question type, such as natural language to SQL, SQL interpretation, etc.
   */
  type: QuestionType;
}

// Every fragment of chat answer
export interface AnswerParts {
  id?: string;
  partType?: AnswerPartsType;
  text?: string;
  loadingText?: string;
  databaseInfo?: IDatabaseBaseInfo;
  chartSchema?: ChartSchema;
  metaData?: IManageResultData;
  recommends?: string[];
  status?: AnswerPartsStatus;
  step: number;
  errorMessage?: string;
  tableMap?: string;
}

export interface UpdateAnswerPartsParams {
  id: number;
  text?: string;
  databaseInfo?: IDatabaseBaseInfo;
  chartSchema?: ChartSchema;
  metaData?: IManageResultData;
  recommends?: string[];
}

// chat answer
export interface AnswerVO {
  id?: number;
  chatId: number;
  questionId: number;
  content?: string;
  parts?: AnswerParts[];
  goodFeedback?: boolean;
  badFeedback?: boolean;
  createTime?: number;
  status?: TaskStatus;
  questionType?: QuestionType;
}

export interface RecommendQuestion {
  data?: string[];
  status: TaskStatus;
}

/**
 * Conversation details VO
 */
export interface ChatDetailVO {
  /**
   *Answer list
   */
  answers?: AnswerVO[];
  /**
   * Question
   */
  question?: QuestionVO;
}

/**
 * Dialogue VO
 */
export interface ChatVO {
  /**
   * ChatID
   */
  id?: number;
  /**
   *chat title
   */
  title?: string;
  /**
   * Description
   */
  description?: string;
  /**
   *Data source id
   */
  dataSourceId?: number;
  /**
   * Conversation details in reverse question-time order.
   * Only returned by the conversation details API.
   */
  chatDetails?: ChatDetailVO[];
  /**
   * chat creation source
   */
  source?: ChatSourceType;
  /**
   * file path
   */
  filePath?: string;
  /**
   * file name
   */
  fileName?: string;
  /**
   * Data source collection ID
   */
  dataSourceCollectionId?: number;
  /**
   * Excel configuration filled in by user
   */
  excelConfig?: any;
}

export interface IChatItem {
  id: number;
  title: string;
  dataSourceCollectionId: number;
}

export interface PromptTableVO {
  /**
   * Database name
   */
  databaseName?: string;
  /**
   * Data source connection ID
   */
  dataSourceId?: number;
  /**
   * schema name
   */
  schemaName?: string;
  /**
   *Table name
   */
  tableName: string;
  /**
   * Table schema
   */
  tableSchema?: string;
  /**
   * Type
   */
  type: 'TABLE';
}

// Message information generated step by step
export interface StepMessageMarkDown {
  type: AnswerPartsType.MARKDOWN;
  content: string;
}

export interface StepMessageDashboard {
  type: AnswerPartsType.DASHBOARD;
  content: IChatItem;
}

export type StepMessageList = (StepMessageMarkDown | StepMessageDashboard)[];

export interface DatabaseInfo extends IDatabaseBaseInfo {
  sql: string;
}

/**
 *Answer VO
 */
// export interface AnswerVO {
//   /**
//    * answer id
//    */
//   id?: number;
//   /**
//    * chat id
//    */
//   chatId?: number;
//   /**
//    * Answer content, JSON or Markdown text
//    */
//   content?: string;
//   /**
//    * Is it bad feedback?
//    */
//   badFeedback?: boolean;
//   /**
//    * Is it good feedback?
//    */
//   goodFeedback?: boolean;
//   /**
//    * Dashboard
//    */
//   dashboard?: DashboardVO;
//   /**
//    * Dashboard id, when the answer result type is dashboard, this field has a value
//    */
//   dashboardId?: number;
//   /**
//    * Whether there is no feedback
//    */
//   noFeedBack?: boolean;
//   /**
//    * Question id
//    */
//   questionId?: number;
//   /**
//    * Whether it needs to be regenerated
//    */
//   regenerate?: boolean;
// }
