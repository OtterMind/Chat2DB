export const chatError = {
  CHAT2DB_KEY_INVALID: 'apikey 不在我们的数据库中需要扫码登录',
  CHAT2DB_KEY_LIMIT: '次数用完了，需要发起推广',
  CHAT2DB_KEY_EXPIRED: '到过期时间了',
  CHAT2DB_SERVICE_BUSY: '这个异常就稍后重试就行了',
  CHAT2DB_AUTH_HEADER_MISSING: '这个是 http 请求 header 没传 Authorization 字段，这个你看要怎么处理',
  CHAT2DB_AUTH_TOKEN_MISSING:
    '这个是 http 请求 header 中 Authorization 后面没有以 Bearer 开头，也是传的认证信息有问题，你看要怎么处理',
  CHAT2DB_SERVICE_ERROR: '这个是出了意料之外的异常要联系管理员',
  CHAT2DB_BAD_JSON_FORMAT: '这个是传的请求不是 json 格式',
  CHAT2DB_HTTP_METHOD_INVALID: '这个是传的请求不是 post 请求，目前给 openai 的请求必须是 post',
};

export const chatErrorCodeArr = Object.keys(chatError);

export const chatErrorToLogin = ['CHAT2DB_KEY_INVALID', 'CHAT2DB_AUTH_HEADER_MISSING', 'CHAT2DB_AUTH_TOKEN_MISSING'];
export const chatErrorForKey = ['CHAT2DB_KEY_LIMIT', 'CHAT2DB_KEY_EXPIRED'];

// Types of Q&A interfaces
export enum QuestionType {
  /**
   * General Q&A on drawers
   */
  ORDINARY_CHAT = 'ORDINARY_CHAT',

  /**
   * sql error report
   */
  SQL_DEBUG = 'SQL_DEBUG',

  /**
   * Convert natural language into SQL
   */
  NL_2_SQL = 'NL_2_SQL',

  /**
   * Explain SQL
   */
  SQL_EXPLAIN = 'SQL_EXPLAIN',

  /**
   *SQL optimization
   */
  SQL_OPTIMIZER = 'SQL_OPTIMIZER',

  /**
   * SQL conversion
   */
  SQL_2_SQL = 'SQL_2_SQL',

  /**
   * Report generation
   */
  DASHBOARD_GENERATION = 'DASHBOARD_GENERATION',

  /**
   * Report generation
   */
  DASHBOARD_GENERATION_STREAM = 'DASHBOARD_GENERATION_STREAM',
  /**
   * Generate filter
   */
  FILTER_GENERATION = 'FILTER_GENERATION',

  /**
   * CRUD generation
   */
  CRUD_GENERATION = 'CRUD_GENERATION',

  /**
   * Data insertion
   */
  DATA_INSERT = 'DATA_INSERT',

  /**
   * SQL prompt
   */
  SQL_PROMPT = 'SQL_PROMPT',

  /**
   * EXCEL query
   */
  EXCEL_CHAT = 'EXCEL_CHAT',

  /**
   * Natural language table creation
   */
  TEXT_TO_CREATE_TABLE_STREAM = 'TEXT_TO_CREATE_TABLE_STREAM',

  /**
   * Natural language modification table
   */
  TEXT_MODIFY_COLUMN = 'TEXT_MODIFY_COLUMN',
}

// Source classification of chat
export enum ChatSourceType {
  /**
   * Data source drawer
   */
  DATASOURCE_DRAWER_CHAT = 'DATASOURCE_DRAWER_CHAT',
  /**
   * Report drawer
   */
  DASHBOARD_DRAWER_CHAT = 'DASHBOARD_DRAWER_CHAT',
  /**
   *
   */
  DRAWER_CHAT = 'DRAWER_CHAT',
  /**
   * Chat with dataSourceId
   */
  DATASOURCE_CHAT = 'DATASOURCE_CONSOLE_CHAT',
  /**
   * Chat without dataSourceId
   */
  SINGLE_TURN_CHAT = 'SINGLE_TURN_CHAT',
}

// Message types supported for rendering in chat
export enum AnswerPartsType {
  // markDown
  MARKDOWN = 'MARKDOWN',
  // dashboard
  DASHBOARD = 'DASHBOARD',
  // data
  DATA = 'DATA',
  // RecommendQuestion
  RECOMMEND_QUESTION = 'RECOMMEND_QUESTION',
  // TABLE
  TABLE = 'TABLE',
  // ERROR
  ERROR = 'ERROR',
  // DATABASE_INFO
  DATABASE_INFO = 'DATABASE_INFO',
  // LOADING
  LOADING = 'LOADING',
  // LOADING_COMPLETE
  LOADING_COMPLETE = 'LOADING_COMPLETE',
}

export enum AnswerPartsStatus {
  LOADING = 'LOADING',
  FINISH = 'FINISH',
  FAIL = 'FAIL',
}
