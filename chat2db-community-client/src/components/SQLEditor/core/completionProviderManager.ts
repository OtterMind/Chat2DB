import * as monaco from 'monaco-editor';
import keywordObj, { IFunctionParameter, IKeyword } from '../helper/keywords';
import { DatabaseTypeCode } from '@/constants';
import { quoteSqlCompletionIdentifier } from '@/utils/databaseJudgments';
import SQLParserService from '@/service/sqlParser';
import {
  isBackendCompletionDatabaseType,
  isBackendCompletionModel,
} from './sqlCompletionModelMode';
import {
  getBackendCompletionItemEffectiveFilterText,
  getBackendCompletionItemInsertText,
  getBackendCompletionItemLabel,
  shouldTriggerQualifiedReferenceCompletion,
} from './sqlCompletionBackendSuggestion';
import {
  applyBackendCompletionSuggestWidth,
  clearBackendCompletionSuggestWidth,
} from './sqlCompletionSuggestWidth';
import {
  shouldTriggerSnippetPlaceholderCompletion,
  type SnippetNavigationSnapshot,
} from './sqlCompletionSnippetNavigation';
import {
  ISimpleColumnVO,
  ISimpleDatabaseVO,
  ISimpleFunctionVO,
  ISimpleProcedureVO,
  ISimpleSchemaVO,
  ISimpleTableVO,
  ISimpleViewVO,
  ISqlCompletionCandidate,
  ISqlCompletionActiveSnippetSlot,
  ISqlCompletionResult,
  SqlCompletionKeywordCase,
  SqlCompletionInsertType,
  SqlCompletionSnippetSlotType,
  ISqlEditorHintVO,
  ITipItemVO,
  SqlStatement,
} from '@/typings/sqlParser';
import { SORT_TEXT, TIP_TYPE } from '../type';
import { IBoundInfo } from '@/typings';
import i18n from '@/i18n';
import { useGlobalStore } from '@/store/global';

const triggerCharacters = [' ', '.', ',', '(', ')', '[', ']', '{', '}'];
const ACTIVATE_SNIPPET_SLOT_COMMAND = 'chat2db.sqlCompletion.activateSnippetSlot';
const TRIGGER_QUALIFIED_REFERENCE_COMPLETION_COMMAND = 'chat2db.sqlCompletion.triggerQualifiedReferenceCompletion';
const SNIPPET_NAVIGATION_SESSION_TTL = 30 * 1000;

type EditorHintsListener = (editorHints: ISqlEditorHintVO[]) => void;
type TrackedSnippetSlot = ISqlCompletionActiveSnippetSlot & {
  modelUri: string;
  modelVersionId: number;
};

interface CompletionCommandPayload {
  modelUri: string;
  slots?: SqlCompletionSnippetSlotType[];
}

interface PreparedTips {
  modelUri: string;
  modelVersionId: number;
  cursor: number;
  suggestions: monaco.languages.CompletionItem[];
}

interface SnippetNavigationSession {
  modelUri: string;
  expiresAt: number;
  timer?: number;
}

interface BackendCompletionResult {
  candidates: ISqlCompletionCandidate[];
  editorHints: ISqlEditorHintVO[];
  replaceStart?: number;
  replaceEnd?: number;
}

interface CandidateReplacementOffsets {
  replaceStart?: number;
  replaceEnd?: number;
}

type FetchTipsParams = {
  model: monaco.editor.ITextModel;
  keywordCase?: SqlCompletionKeywordCase;
  activeSnippetSlot?: ISqlCompletionActiveSnippetSlot;
} & (
  | {
      sql: string;
      cursor: number;
    }
  | {
      beforeSql: string;
      afterSql: string;
    }
  );

type TipsQuerySqlPayload = { sql: string; cursor: number } | { beforeSql: string; afterSql: string };

interface WindowedSqlContext {
  beforeSql: string;
  afterSql: string;
}

type ModelDBInfoSignature = string;

class CompletionProviderManager {
  private static instance: CompletionProviderManager | null = null;

  private builtInKeywordsProvider: monaco.IDisposable | null = null;
  private builtInFunctionsProvider: monaco.IDisposable | null = null;

  private databaseProvider: monaco.IDisposable | null = null;
  private schemaProvider: monaco.IDisposable | null = null;

  private tableProvider: monaco.IDisposable | null = null;
  private columnProvider: monaco.IDisposable | null = null;

  private tipsProvider: monaco.IDisposable | null = null;

  private functionProvider: monaco.IDisposable | null = null;
  private viewProvider: monaco.IDisposable | null = null;
  private producerProvider: monaco.IDisposable | null = null;
  private triggerProvider: monaco.IDisposable | null = null;

  public sqlStatementList: SqlStatement[] = [];
  private dbInfo: IBoundInfo | null = {};
  private dbInfoChangeSeq = 0;
  private modelDbInfoMap = new Map<string, IBoundInfo>();

  private originTableList: ISimpleTableVO[] = [];
  private originColumnList: ISimpleColumnVO[] = [];

  private cachedTips: monaco.languages.CompletionItem[] = [];
  private cachedTipsProvider: monaco.IDisposable | null = null;
  private editorHintsListeners = new Map<string, EditorHintsListener>();
  private editorHintsRequestSeqMap = new Map<string, number>();
  private activeSnippetSlot: TrackedSnippetSlot | null = null;
  private preparedTips: PreparedTips | null = null;
  private snippetSlotCommand: monaco.IDisposable | null = null;
  private qualifiedReferenceCompletionCommand: monaco.IDisposable | null = null;
  private snippetNavigationSession: SnippetNavigationSession | null = null;

  private constructor() {
    // this.registerTipsProvider();
    this.registerSnippetSlotCommand();
    this.registerQualifiedReferenceCompletionCommand();
  }

  public static getInstance(dbInfo: IBoundInfo): CompletionProviderManager {
    if (!CompletionProviderManager.instance) {
      CompletionProviderManager.instance = new CompletionProviderManager();
    }
    if (dbInfo) {
      CompletionProviderManager.instance.changeDBInfo(dbInfo);
    }
    return CompletionProviderManager.instance;
  }

  public clearAllProviders = () => {
    this.disposeProvider(this.builtInKeywordsProvider);
    this.disposeProvider(this.builtInFunctionsProvider);

    this.disposeProvider(this.databaseProvider);
    this.disposeProvider(this.schemaProvider);
    this.disposeProvider(this.tableProvider);
    this.disposeProvider(this.columnProvider);
    this.disposeProvider(this.functionProvider);
    this.disposeProvider(this.viewProvider);
    this.disposeProvider(this.producerProvider);
    this.disposeProvider(this.triggerProvider);
    this.disposeProvider(this.tipsProvider);
    this.disposeProvider(this.cachedTipsProvider);
    this.disposeProvider(this.snippetSlotCommand);
    this.disposeProvider(this.qualifiedReferenceCompletionCommand);
    this.cachedTipsProvider = null;
    this.snippetSlotCommand = null;
    this.qualifiedReferenceCompletionCommand = null;
    clearBackendCompletionSuggestWidth();
    this.clearActiveSnippetSlot();
    this.clearSnippetNavigationSession();
  };

  public changeDBInfo = async (dbInfo: IBoundInfo) => {
    const changeSeq = ++this.dbInfoChangeSeq;
    this.dbInfo = dbInfo;
    this.clearAllProviders();
    this.cachedTips = [];
    this.clearActiveSnippetSlot();
    this.originTableList = [];
    this.originColumnList = [];
    this.registerSnippetSlotCommand();
    this.registerQualifiedReferenceCompletionCommand();

    const { consoleId, dataSourceId, databaseType, databaseName, schemaName } = dbInfo;
    const backendCompletionMode = isBackendCompletionDatabaseType(databaseType);
    if (databaseType && !backendCompletionMode) {
      this.registerBuiltInKeywordsProvider();
      this.registerBuiltInFunctionsProvider();
    }

    if (!consoleId || !dataSourceId) return;
    if (backendCompletionMode) {
      this.registerTipsProvider();
      return;
    }

    // Call /api/sql_parser/get_keywords.
    const databaseAndSchema = await SQLParserService.queryDatabaseAndSchema({
      consoleId,
      dataSourceId,
      databaseName,
      schemaName,
    });
    if (changeSeq !== this.dbInfoChangeSeq) {
      return;
    }

    // Guard because SQLParserService.queryDatabaseAndSchema can fail here.
    if (!databaseAndSchema) return;

    const { databases, schemas, tables, views, functions, procedures } = databaseAndSchema;

    this.originTableList = tables;
    this.registerDatabaseProvider(databases);
    this.registerSchemaProvider(schemas);
    this.registerTableProvider(tables);
    this.registerViewProvider(views);
    this.registerFunctionProvider(functions);
    this.registerProcedureProvider(procedures);
    this.registerTipsProvider();
    // this.registerCachedTipsProvider();

    this.registerParserColumnProvider();
  };

  public onParserChange = (sqlStatementList: SqlStatement[], curStatement?: SqlStatement) => {
    this.sqlStatementList = sqlStatementList;
    if (curStatement) {
      const { tableColumns } = curStatement;
      const columns = (tableColumns || []).reduce((acc: ISimpleColumnVO[], cur) => {
        acc.push(...cur.simpleColumns);
        return acc;
      }, []);
      if (!columns.length) {
        return;
      }

      setTimeout(() => {
        // this.registerParserColumnProvider(columns);
        this.originColumnList = columns ?? [];
      }, 1000);
    }
  };

  public bindModelDBInfo(model: monaco.editor.ITextModel | null | undefined, dbInfo: IBoundInfo): void {
    if (!model) {
      return;
    }
    const modelUri = model.uri.toString();
    const current = this.modelDbInfoMap.get(modelUri);
    if (this.getDBInfoSignature(current) !== this.getDBInfoSignature(dbInfo)) {
      this.bumpEditorHintsRequestSeq(modelUri);
    }
    this.modelDbInfoMap.set(modelUri, dbInfo);
  }

  public clearModelDBInfo(model: monaco.editor.ITextModel | null | undefined): void {
    if (!model) {
      return;
    }
    const modelUri = model.uri.toString();
    this.modelDbInfoMap.delete(modelUri);
    this.editorHintsListeners.delete(modelUri);
    this.editorHintsRequestSeqMap.delete(modelUri);
    if (this.activeSnippetSlot?.modelUri === modelUri) {
      this.clearActiveSnippetSlot();
    }
    if (this.snippetNavigationSession?.modelUri === modelUri) {
      this.clearSnippetNavigationSession();
    }
    clearBackendCompletionSuggestWidth();
  }

  public setEditorHintsListener(
    listener: EditorHintsListener | null,
    model?: monaco.editor.ITextModel | null,
  ): void {
    if (!model) {
      return;
    }
    const modelUri = model.uri.toString();
    if (listener) {
      this.editorHintsListeners.set(modelUri, listener);
      return;
    }
    this.editorHintsListeners.delete(modelUri);
  }

  public clearEditorHintsListener(
    listener: EditorHintsListener,
    model?: monaco.editor.ITextModel | null,
  ): void {
    if (!model) {
      return;
    }
    const modelUri = model.uri.toString();
    if (this.editorHintsListeners.get(modelUri) === listener) {
      this.editorHintsListeners.delete(modelUri);
    }
  }

  public bindSnippetPlaceholderCompletion(
    editor: monaco.editor.ICodeEditor | null | undefined,
  ): monaco.IDisposable | null {
    if (!editor) {
      return null;
    }
    return editor.onKeyDown((event) => {
      if (event.keyCode !== monaco.KeyCode.Tab) {
        return;
      }
      const model = editor.getModel();
      if (
        !model ||
        !this.hasActiveSnippetNavigationSession(model.uri.toString()) ||
        !this.isEditorInSnippetMode(editor)
      ) {
        return;
      }
      const before = this.getSnippetNavigationSnapshot(model, editor);
      if (!before) {
        return;
      }
      const previousTimer = this.snippetNavigationSession?.timer;
      if (previousTimer !== undefined) {
        window.clearTimeout(previousTimer);
      }
      const timer = window.setTimeout(() => {
        this.handleSnippetPlaceholderNavigation(editor, before);
      }, 0);
      if (this.snippetNavigationSession?.modelUri === model.uri.toString()) {
        this.snippetNavigationSession.timer = timer;
      }
    });
  }

  public async refreshEditorHints(params: {
    model: monaco.editor.ITextModel;
    sql: string;
    cursor: number;
  }): Promise<ISqlEditorHintVO[] | null> {
    const modelUri = params.model.uri.toString();
    this.bumpEditorHintsRequestSeq(modelUri);
    const result = await this.fetchTipsResult(params);
    if (!result) {
      return null;
    }
    return result?.editorHints || [];
  }

  private constructBackendSuggestion(
    candidate: ISqlCompletionCandidate,
    model: monaco.editor.ITextModel,
    result?: Pick<BackendCompletionResult, 'replaceStart' | 'replaceEnd'> | null,
    activeSnippetSlot?: ISqlCompletionActiveSnippetSlot,
  ): monaco.languages.CompletionItem | null {
    const replacementOffsets = {
      replaceStart: candidate.replaceStart ?? activeSnippetSlot?.replaceStart ?? result?.replaceStart,
      replaceEnd: candidate.replaceEnd ?? activeSnippetSlot?.replaceEnd ?? result?.replaceEnd,
    };
    const replacementRange = this.getCandidateReplacementRange(replacementOffsets, model, { strict: true });
    const normalizedInsertText = getBackendCompletionItemInsertText(
      candidate,
      this.isUserVariableMarkerAlreadyTyped(candidate, model, replacementOffsets),
    );
    const suggestion = {
      label: getBackendCompletionItemLabel(candidate),
      kind: this.getBackendCompletionItemKind(candidate.type),
      insertText: normalizedInsertText,
      filterText: getBackendCompletionItemEffectiveFilterText(candidate, normalizedInsertText),
      sortText: candidate.sortText,
    } as monaco.languages.CompletionItem;
    const insertTextRules = this.getBackendInsertTextRules(candidate.insertType);
    if (insertTextRules !== undefined) {
      suggestion.insertTextRules = insertTextRules;
    }
    if (replacementRange) {
      suggestion.range = replacementRange;
    } else if (
      this.hasReplacementOffsets(candidate) ||
      this.hasReplacementOffsets(activeSnippetSlot) ||
      this.hasReplacementOffsets(result)
    ) {
      return null;
    }
    const snippetSlotCommand = this.getSnippetSlotCommand(candidate, model);
    if (snippetSlotCommand) {
      suggestion.command = snippetSlotCommand;
    }
    const qualifiedReferenceCompletionCommand = this.getQualifiedReferenceCompletionCommand(candidate, model);
    if (qualifiedReferenceCompletionCommand) {
      suggestion.command = qualifiedReferenceCompletionCommand;
    }
    return suggestion;
  }

  private constructSuggestion(
    item: ITipItemVO,
    model?: monaco.editor.ITextModel,
  ): monaco.languages.CompletionItem {
    const { type, insertTextRules = monaco.languages.CompletionItemInsertTextRule.None, sortText } = item;
    // console.log(item.value, sortText, type);
    const suggestion = {
      label: {
        label: item.value,
        detail: this.getCompletionItemDetail(item, type),
        description: item.description || this.getTipDescription(item, type),
      },
      // ...(item.comment
      //   ? {
      //       documentation: {
      //         value: item.comment,
      //         isTrusted: true,
      //       },
      //     }
      //   : {}),
      kind: this.getCompletionItemKind(type),
      insertText: item.insertText || this.handleAllTypeSpecialName(item.value, item.type),
      insertTextRules,
      sortText,
    } as monaco.languages.CompletionItem;
    const replacementRange = this.getCandidateReplacementRange(item, model);
    if (replacementRange) {
      suggestion.range = replacementRange;
    }
    const snippetSlotCommand = this.getSnippetSlotCommand(item, model);
    if (snippetSlotCommand) {
      suggestion.command = snippetSlotCommand;
    }
    const qualifiedReferenceCompletionCommand = this.getQualifiedReferenceCompletionCommand(item, model);
    if (qualifiedReferenceCompletionCommand) {
      suggestion.command = qualifiedReferenceCompletionCommand;
    }
    return suggestion;
  }

  private getCompletionItemDetail(item: ITipItemVO, type: TIP_TYPE): string {
    if ([TIP_TYPE.COLUMN, TIP_TYPE.JOIN_CLAUSE].includes(type)) {
      return this.getTipsDetail(item, type);
    }
    return item.detail || this.getTipsDetail(item, type);
  }

  private getTipsDetail(item: ITipItemVO, type: TIP_TYPE): string {
    let detail = '';
    switch (type) {
      case TIP_TYPE.DATABASE:
        detail = `(${item.datasourceName})`;
        break;
      case TIP_TYPE.SCHEMA:
        detail = `(${item.datasourceName})`;
        break;
      case TIP_TYPE.TABLE:
        detail = `(${item.databaseName || item.schemaName})`;
        break;
      case TIP_TYPE.VIEW:
        detail = `(${item.databaseName || item.schemaName})`;
        break;
      case TIP_TYPE.COLUMN:
        detail = this.wrapDetail(item.tableAlias || item.tableName);
        break;
      case TIP_TYPE.JOIN_CLAUSE:
        detail = this.wrapDetail(item.tableAlias || item.tableName);
        break;
      default:
        break;
    }

    return detail;
  }

  private wrapDetail(value?: string): string {
    return value ? `(${value})` : '';
  }

  private getTipDescription(item: ITipItemVO, type: TIP_TYPE): string {
    let description = '';
    switch (type) {
      case TIP_TYPE.KEYWORD:
        description = i18n('monaco.completion.keyword');
        break;
      case TIP_TYPE.DATABASE:
        description = i18n('monaco.completion.database');
        break;
      case TIP_TYPE.SCHEMA:
        description = i18n('monaco.completion.schema');
        break;
      case TIP_TYPE.TABLE:
        description = i18n('monaco.completion.table');
        break;
      case TIP_TYPE.COLUMN:
        description = item?.dataType ?? i18n('monaco.completion.column');
        break;
      case TIP_TYPE.VIEW:
        description = i18n('monaco.completion.view');
        break;
      case TIP_TYPE.FUNCTION:
        description = i18n('monaco.completion.function');
        break;
      case TIP_TYPE.PARAMETER:
        // description = i18n('monaco.completion.parameter');
        break;
      case TIP_TYPE.PROCEDURE:
        description = i18n('monaco.completion.procedure');
        break;
      case TIP_TYPE.TRIGGER:
        description = i18n('monaco.completion.trigger');
        break;
      case TIP_TYPE.JOIN_CLAUSE:
        description = i18n('monaco.completion.joinClause');
        break;
      default:
        description = '';
        break;
    }
    return description;
  }

  private getCompletionItemKind(type: TIP_TYPE): monaco.languages.CompletionItemKind {
    switch (type) {
      case TIP_TYPE.SNIPPET:
        return monaco.languages.CompletionItemKind.Snippet;
      case TIP_TYPE.DATABASE:
        return monaco.languages.CompletionItemKind.Method;
      case TIP_TYPE.SCHEMA:
        return monaco.languages.CompletionItemKind.Module;
      case TIP_TYPE.TABLE:
        return monaco.languages.CompletionItemKind.Class;
      case TIP_TYPE.COLUMN:
        return monaco.languages.CompletionItemKind.Field;
      case TIP_TYPE.VIEW:
        return monaco.languages.CompletionItemKind.Interface;
      case TIP_TYPE.FUNCTION:
        return monaco.languages.CompletionItemKind.Function;
      case TIP_TYPE.PROCEDURE:
        return monaco.languages.CompletionItemKind.Unit;
      case TIP_TYPE.PARAMETER:
      case TIP_TYPE.VARIABLE:
      case TIP_TYPE.ALIAS:
        // Monaco word suggestions are disabled for SQL editors. Keep backend-owned
        // variable-like candidates as Keyword and hide the icon through completionIcon.less.
        return monaco.languages.CompletionItemKind.Keyword;
      case TIP_TYPE.KEYWORD:
        return monaco.languages.CompletionItemKind.Keyword;
      case TIP_TYPE.TYPE:
        return monaco.languages.CompletionItemKind.TypeParameter;
      case TIP_TYPE.EVENT:
      case TIP_TYPE.TRIGGER:
        return monaco.languages.CompletionItemKind.Event;
      case TIP_TYPE.ALL_COLUMN:
        return monaco.languages.CompletionItemKind.Field;
      default:
        return monaco.languages.CompletionItemKind.Keyword;
    }
  }
  /**
   * Build built-in keyword completions
   * @param databaseCode
   * @returns
   */
  private handleKeywordCompletionItems = (keywords: ITipItemVO[]) => {
    return (keywords || []).map((keyword) => this.constructSuggestion(keyword));
  };

  /**
   * Build built-in function completions
   * @param databaseCode
   * @returns
   */
  private handleFunctionCompletionItem = (functionTips: ITipItemVO[]) => {
    return (functionTips || []).map((functionTip) => this.constructSuggestion(functionTip));
  };

  /**
   * Build database completions
   */
  private handleDatabaseCompletionItems = (databases: ITipItemVO[]) => {
    return (databases || []).map((database) => this.constructSuggestion(database));
  };

  /**
   * Build schema completions
   */
  private handleSchemaCompletionItems = (schemas: ITipItemVO[]) => {
    return (schemas || []).map((schema) => this.constructSuggestion(schema));
  };

  private handleTableProvider = (tables: ITipItemVO[]) => {
    return (tables || []).map((table) => this.constructSuggestion(table));
  };

  private filterCachedTips(suggestions: monaco.languages.CompletionItem[]): monaco.languages.CompletionItem[] {
    const cachedTipValues = new Set(this.cachedTips.map((tip) => this.getCompletionItemLabelText(tip)));
    return suggestions.filter((suggestion) => !cachedTipValues.has(this.getCompletionItemLabelText(suggestion)));
  }

  private getCompletionItemLabelText(suggestion: monaco.languages.CompletionItem): string {
    return typeof suggestion.label === 'string' ? suggestion.label : suggestion.label.label;
  }

  private getSqlCompletionKeywordCase(): SqlCompletionKeywordCase {
    return useGlobalStore.getState().editorSettings?.keywordCase ? 'UPPER' : 'LOWER';
  }

  private async fetchTipsResult(params: FetchTipsParams): Promise<ISqlCompletionResult | null> {
    const { model, keywordCase, activeSnippetSlot } = params;
    const dbInfo = this.getDBInfoForModel(model);
    const { consoleId, dataSourceId, databaseName, schemaName, databaseType } = dbInfo || {};

    if (
      !consoleId ||
      !dataSourceId
      // || (supportDatabase && !databaseName) || (supportSchema && !schemaName)
    ) {
      return null;
    }

    try {
      return await SQLParserService.queryTips({
        consoleId,
        ...this.getTipsQuerySqlPayload(params),
        dataSourceId,
        databaseName,
        schemaName,
        needFullName: useGlobalStore.getState().editorSettings?.completion?.includes(databaseType || ''),
        ...(keywordCase ? { keywordCase } : {}),
        activeSnippetSlot,
      });
    } catch (error) {
      console.error('Error fetching tips:', error);
      return null;
    }
  }

  private getTipsQuerySqlPayload(
    params: FetchTipsParams,
  ): TipsQuerySqlPayload {
    if ('sql' in params) {
      return {
        sql: params.sql,
        cursor: params.cursor,
      };
    }
    return {
      beforeSql: params.beforeSql,
      afterSql: params.afterSql,
    };
  }

  private getDBInfoForModel(model: monaco.editor.ITextModel): IBoundInfo | null {
    return this.modelDbInfoMap.get(model.uri.toString()) || null;
  }

  private getDBInfoSignature(dbInfo: IBoundInfo | null | undefined): ModelDBInfoSignature {
    if (!dbInfo) {
      return '';
    }
    return [
      dbInfo.consoleId ?? '',
      dbInfo.dataSourceId ?? '',
      dbInfo.databaseType ?? '',
      dbInfo.databaseName ?? '',
      dbInfo.schemaName ?? '',
    ].join('|');
  }

  private bumpEditorHintsRequestSeq(modelUri: string): number {
    const requestSeq = (this.editorHintsRequestSeqMap.get(modelUri) || 0) + 1;
    this.editorHintsRequestSeqMap.set(modelUri, requestSeq);
    return requestSeq;
  }

  private getEditorHintsRequestSeq(modelUri: string): number {
    return this.editorHintsRequestSeqMap.get(modelUri) || 0;
  }

  private notifyEditorHints(model: monaco.editor.ITextModel, editorHints: ISqlEditorHintVO[]): void {
    this.editorHintsListeners.get(model.uri.toString())?.(editorHints);
  }

  private async fetchBackendCompletion(params: {
    model: monaco.editor.ITextModel;
    sql: string;
    cursor: number;
    activeSnippetSlot?: ISqlCompletionActiveSnippetSlot;
  }): Promise<BackendCompletionResult | null> {
    const modelUri = params.model.uri.toString();
    const requestSeq = this.bumpEditorHintsRequestSeq(modelUri);
    const requestDBInfoSignature = this.getDBInfoSignature(this.getDBInfoForModel(params.model));
    const result = await this.fetchTipsResult({
      ...params,
      keywordCase: this.getSqlCompletionKeywordCase(),
    });
    if (!result) {
      return null;
    }
    if (requestDBInfoSignature !== this.getDBInfoSignature(this.getDBInfoForModel(params.model))) {
      return null;
    }
    if (this.isBackendCompletion(params.model) && requestSeq === this.getEditorHintsRequestSeq(modelUri)) {
      this.notifyEditorHints(params.model, result?.editorHints || []);
    }
    return {
      candidates: result.candidates || [],
      editorHints: result.editorHints || [],
      replaceStart: result.replaceStart,
      replaceEnd: result.replaceEnd,
    };
  }

  private async fetchLegacyBackendCompletion(
    model: monaco.editor.ITextModel,
    position: monaco.Position,
  ): Promise<BackendCompletionResult | null> {
    const result = await this.fetchTipsResult({
      model,
      ...this.getWindowedSqlContext(model, position),
    });
    if (!result) {
      return null;
    }
    return {
      candidates: result.candidates || [],
      editorHints: [],
    };
  }

  private sqlCompletionCandidateToTip(
    candidate: ISqlCompletionCandidate,
    result?: CandidateReplacementOffsets | null,
    activeSnippetSlot?: ISqlCompletionActiveSnippetSlot,
  ): ITipItemVO {
    const type = this.normalizeTipType(candidate.type);
    return {
      value: candidate.label || candidate.insertText || '',
      insertText: candidate.insertText,
      insertTextRules: this.getBackendInsertTextRules(candidate.insertType),
      replaceStart: candidate.replaceStart ?? activeSnippetSlot?.replaceStart ?? result?.replaceStart,
      replaceEnd: candidate.replaceEnd ?? activeSnippetSlot?.replaceEnd ?? result?.replaceEnd,
      type,
      dataType: candidate.dataType || candidate.objectType || '',
      comment: candidate.comment || '',
      datasourceName: candidate.datasourceName || '',
      databaseName: candidate.databaseName,
      schemaName: candidate.schemaName,
      tableName: candidate.tableName,
      tableAlias: candidate.tableAlias,
      columnName: candidate.columnName,
      detail: candidate.detail,
      description: candidate.description,
      sortText: candidate.sortText,
      snippetSlots: candidate.snippetSlots,
    };
  }

  private getBackendInsertTextRules(
    insertType?: SqlCompletionInsertType,
  ): monaco.languages.CompletionItemInsertTextRule | undefined {
    if (insertType === 'SNIPPET') {
      return monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet;
    }
    return undefined;
  }

  private normalizeTipType(type?: string): TIP_TYPE {
    return Object.values(TIP_TYPE).includes(type as TIP_TYPE) ? (type as TIP_TYPE) : TIP_TYPE.COLUMN;
  }

  private getBackendCompletionItemKind(type?: string): monaco.languages.CompletionItemKind {
    if (!Object.values(TIP_TYPE).includes(type as TIP_TYPE)) {
      // SQL editors disable Monaco word suggestions (`showWords: false`), which
      // also filters CompletionItemKind.Text. Backend candidates are semantic
      // results, so keep unknown backend types visible instead of classifying
      // them as word suggestions.
      return monaco.languages.CompletionItemKind.Keyword;
    }
    return this.getCompletionItemKind(type as TIP_TYPE);
  }

  private isUserVariableMarkerAlreadyTyped(
    candidate: ISqlCompletionCandidate,
    model: monaco.editor.ITextModel,
    replacementOffsets: CandidateReplacementOffsets,
  ): boolean {
    if (candidate.type !== TIP_TYPE.VARIABLE) {
      return false;
    }
    if (replacementOffsets.replaceStart === undefined || replacementOffsets.replaceStart <= 0) {
      return false;
    }
    const start = model.getPositionAt(replacementOffsets.replaceStart - 1);
    const end = model.getPositionAt(replacementOffsets.replaceStart);
    return model.getValueInRange({
      startLineNumber: start.lineNumber,
      startColumn: start.column,
      endLineNumber: end.lineNumber,
      endColumn: end.column,
    }) === '@';
  }

  private filterTable = (tips: ITipItemVO[]) => {
    // Find tips that are not in this.originTableList.
    const filteredTips = (tips || []).filter((tip) => {
      return !this.originTableList.some(
        (originalTip) =>
          tip.type === TIP_TYPE.TABLE &&
          originalTip.tableName === tip.tableName &&
          originalTip.databaseName === tip.databaseName &&
          originalTip.datasourceName === tip.datasourceName,
      );
    });

    return filteredTips;
  };

  private filterColumn = (tips: ITipItemVO[]) => {
    const filteredTips = (tips || []).filter((tip) => {
      return !this.originColumnList.some(
        (originalTip) =>
          tip.type === TIP_TYPE.COLUMN &&
          originalTip.columnName === tip.columnName &&
          originalTip.tableName === tip.tableName &&
          originalTip.databaseName === tip.databaseName &&
          originalTip.datasourceName === tip.datasourceName,
      );
    });

    return filteredTips;
  };

  private disposeProvider(provider: monaco.IDisposable | null): void {
    if (provider) {
      provider.dispose();
    }
  }

  private registerSnippetSlotCommand(): void {
    if (this.snippetSlotCommand) {
      return;
    }
    this.snippetSlotCommand = monaco.editor.registerCommand(
      ACTIVATE_SNIPPET_SLOT_COMMAND,
      (_: unknown, payload?: CompletionCommandPayload) => {
        this.activateSnippetSlot(payload);
      },
    );
  }

  private registerQualifiedReferenceCompletionCommand(): void {
    if (this.qualifiedReferenceCompletionCommand) {
      return;
    }
    this.qualifiedReferenceCompletionCommand = monaco.editor.registerCommand(
      TRIGGER_QUALIFIED_REFERENCE_COMPLETION_COMMAND,
      (_: unknown, payload?: CompletionCommandPayload) => {
        this.triggerQualifiedReferenceCompletion(payload);
      },
    );
  }

  private getSnippetSlotCommand(
    item: Pick<ITipItemVO, 'insertText' | 'snippetSlots' | 'type'> | Pick<ISqlCompletionCandidate, 'insertText' | 'snippetSlots' | 'type'>,
    model?: monaco.editor.ITextModel,
  ): monaco.languages.Command | undefined {
    if (item.type !== TIP_TYPE.SNIPPET || !model || !this.shouldTriggerSuggestAfterSnippet(item)) {
      return undefined;
    }
    return {
      id: ACTIVATE_SNIPPET_SLOT_COMMAND,
      title: ACTIVATE_SNIPPET_SLOT_COMMAND,
      arguments: [
        {
          modelUri: model.uri.toString(),
          slots: item.snippetSlots,
        },
      ],
    };
  }

  private getQualifiedReferenceCompletionCommand(
    item: Pick<ITipItemVO, 'insertText' | 'type'> | Pick<ISqlCompletionCandidate, 'insertText' | 'type'>,
    model?: monaco.editor.ITextModel,
  ): monaco.languages.Command | undefined {
    if (!model || !shouldTriggerQualifiedReferenceCompletion(item)) {
      return undefined;
    }
    return {
      id: TRIGGER_QUALIFIED_REFERENCE_COMPLETION_COMMAND,
      title: TRIGGER_QUALIFIED_REFERENCE_COMPLETION_COMMAND,
      arguments: [
        {
          modelUri: model.uri.toString(),
        },
      ],
    };
  }

  private shouldTriggerSuggestAfterSnippet(item: Pick<ITipItemVO, 'insertText' | 'snippetSlots'>): boolean {
    return Boolean(item.snippetSlots?.length || this.hasSnippetTabstop(item.insertText));
  }

  private hasSnippetTabstop(insertText?: string): boolean {
    return /\$(?:\d+|\{\d+(?::[^}]*)?\})/.test(insertText || '');
  }

  private activateSnippetSlot(payload?: CompletionCommandPayload): void {
    if (!payload?.modelUri) {
      this.clearActiveSnippetSlot();
      this.clearSnippetNavigationSession();
      return;
    }
    this.trackSnippetNavigationSession(payload.modelUri);
    window.setTimeout(async () => {
      const editor = this.findEditor(payload.modelUri);
      const model = editor?.getModel();
      const selection = editor?.getSelection();
      if (!editor || !model || !selection) {
        this.clearActiveSnippetSlot();
        this.clearSnippetNavigationSession();
        return;
      }
      if (payload.slots?.length) {
        const slotType = payload.slots[0];
        if (!slotType) {
          this.clearActiveSnippetSlot();
          return;
        }
        const start = model.getOffsetAt(selection.getStartPosition());
        const end = model.getOffsetAt(selection.getEndPosition());
        this.activeSnippetSlot = {
          modelUri: payload.modelUri,
          modelVersionId: model.getVersionId(),
          type: slotType,
          replaceStart: Math.min(start, end),
          replaceEnd: Math.max(start, end),
        };
      } else {
        this.clearActiveSnippetSlot();
      }

      const position = editor.getPosition();
      if (!position) {
        return;
      }
      const activeSnippetSlot = this.getActiveSnippetSlotForRequest(model, position);
      const prepared = await this.prepareCompletionSuggestions(model, position, activeSnippetSlot);
      if (!prepared) {
        return;
      }
      editor.trigger(ACTIVATE_SNIPPET_SLOT_COMMAND, 'editor.action.triggerSuggest', {});
    }, 0);
  }

  private triggerQualifiedReferenceCompletion(payload?: CompletionCommandPayload): void {
    if (!payload?.modelUri) {
      return;
    }
    window.setTimeout(async () => {
      const editor = this.findEditor(payload.modelUri);
      const model = editor?.getModel();
      const position = editor?.getPosition();
      if (!editor || !model || !position) {
        return;
      }
      this.clearActiveSnippetSlot();
      const prepared = await this.prepareCompletionSuggestions(model, position);
      if (!prepared) {
        return;
      }
      const currentPosition = editor.getPosition();
      if (
        !currentPosition
        || model.getVersionId() !== prepared.modelVersionId
        || model.getOffsetAt(currentPosition) !== prepared.cursor
      ) {
        return;
      }
      editor.trigger(TRIGGER_QUALIFIED_REFERENCE_COMPLETION_COMMAND, 'editor.action.triggerSuggest', {});
    }, 0);
  }

  private async handleSnippetPlaceholderNavigation(
    editor: monaco.editor.ICodeEditor,
    before: SnippetNavigationSnapshot,
  ): Promise<void> {
    const model = editor.getModel();
    if (!model || !this.hasActiveSnippetNavigationSession(model.uri.toString())) {
      return;
    }
    if (!this.isEditorInSnippetMode(editor)) {
      return;
    }
    const after = this.getSnippetNavigationSnapshot(model, editor);
    if (!shouldTriggerSnippetPlaceholderCompletion(before, after)) {
      return;
    }
    this.trackSnippetNavigationSession(model.uri.toString());
    const position = editor.getPosition();
    if (!position) {
      return;
    }
    const activeSnippetSlot = this.getActiveSnippetSlotForRequest(model, position);
    const prepared = await this.prepareCompletionSuggestions(model, position, activeSnippetSlot);
    if (!prepared) {
      return;
    }
    const currentPosition = editor.getPosition();
    if (
      !currentPosition
      || model.getVersionId() !== prepared.modelVersionId
      || model.getOffsetAt(currentPosition) !== prepared.cursor
    ) {
      return;
    }
    editor.trigger(ACTIVATE_SNIPPET_SLOT_COMMAND, 'editor.action.triggerSuggest', {});
  }

  private getSnippetNavigationSnapshot(
    model: monaco.editor.ITextModel,
    editor: monaco.editor.ICodeEditor,
  ): SnippetNavigationSnapshot | null {
    const position = editor.getPosition();
    const selection = editor.getSelection();
    if (!position || !selection) {
      return null;
    }
    const selectionStart = model.getOffsetAt(selection.getStartPosition());
    const selectionEnd = model.getOffsetAt(selection.getEndPosition());
    return {
      modelVersionId: model.getVersionId(),
      cursor: model.getOffsetAt(position),
      selectionStart: Math.min(selectionStart, selectionEnd),
      selectionEnd: Math.max(selectionStart, selectionEnd),
    };
  }

  private trackSnippetNavigationSession(modelUri: string): void {
    const existingTimer = this.snippetNavigationSession?.timer;
    this.snippetNavigationSession = {
      modelUri,
      expiresAt: Date.now() + SNIPPET_NAVIGATION_SESSION_TTL,
      timer: existingTimer,
    };
  }

  private hasActiveSnippetNavigationSession(modelUri: string): boolean {
    const session = this.snippetNavigationSession;
    if (!session || session.modelUri !== modelUri) {
      return false;
    }
    if (session.expiresAt < Date.now()) {
      this.clearSnippetNavigationSession();
      return false;
    }
    return true;
  }

  private clearSnippetNavigationSession(): void {
    if (this.snippetNavigationSession?.timer !== undefined) {
      window.clearTimeout(this.snippetNavigationSession.timer);
    }
    this.snippetNavigationSession = null;
  }

  private isEditorInSnippetMode(editor: monaco.editor.ICodeEditor): boolean {
    const snippetController = editor.getContribution('snippetController2') as { isInSnippet?: () => boolean } | null;
    return Boolean(snippetController?.isInSnippet?.());
  }

  private async prepareCompletionSuggestions(
    model: monaco.editor.ITextModel,
    position: monaco.Position,
    activeSnippetSlot?: ISqlCompletionActiveSnippetSlot,
  ): Promise<PreparedTips | null> {
    const cursor = model.getOffsetAt(position);
    const backendCompletionMode = this.isBackendCompletion(model);
    const backendCompletion = backendCompletionMode
      ? await this.fetchBackendCompletion({
          model,
          sql: model.getValue(),
          cursor,
          activeSnippetSlot,
        })
      : await this.fetchLegacyBackendCompletion(model, position);
    if (!backendCompletion) {
      this.preparedTips = null;
      if (backendCompletionMode) {
        clearBackendCompletionSuggestWidth();
      }
      return null;
    }
    const suggestions = backendCompletionMode
      ? this.toBackendSuggestions(backendCompletion, model, activeSnippetSlot)
      : this.toSuggestions(
          backendCompletion.candidates.map((candidate) =>
            this.sqlCompletionCandidateToTip(candidate, backendCompletion, activeSnippetSlot),
          ),
          model,
        );
    if (!suggestions.length) {
      this.preparedTips = null;
      if (backendCompletionMode) {
        clearBackendCompletionSuggestWidth();
      }
      return null;
    }
    if (backendCompletionMode) {
      applyBackendCompletionSuggestWidth(suggestions, this.findEditor(model.uri.toString()));
    }
    const prepared = {
      modelUri: model.uri.toString(),
      modelVersionId: model.getVersionId(),
      cursor,
      suggestions,
    };
    this.preparedTips = prepared;
    if (!backendCompletionMode) {
      this.cachedTips = suggestions.map((tip) => this.withoutCandidateReplacementRange(tip));
    }
    return prepared;
  }

  private consumePreparedTips(
    model: monaco.editor.ITextModel,
    cursor: number,
  ): monaco.languages.CompletionItem[] | null {
    if (!this.preparedTips
      || this.preparedTips.modelUri !== model.uri.toString()
      || this.preparedTips.modelVersionId !== model.getVersionId()
      || this.preparedTips.cursor !== cursor) {
      return null;
    }
    const suggestions = this.preparedTips.suggestions;
    this.preparedTips = null;
    return suggestions;
  }

  private getActiveSnippetSlotForRequest(
    model: monaco.editor.ITextModel,
    position: monaco.Position,
  ): ISqlCompletionActiveSnippetSlot | undefined {
    if (!this.activeSnippetSlot || this.activeSnippetSlot.modelUri !== model.uri.toString()) {
      return undefined;
    }
    const cursor = model.getOffsetAt(position);
    const replaceStart = this.activeSnippetSlot.replaceStart ?? cursor;
    const replaceEnd = this.activeSnippetSlot.replaceEnd ?? replaceStart;
    const modelLength = model.getValueLength();
    const versionChanged = this.activeSnippetSlot.modelVersionId !== model.getVersionId();
    if (replaceStart > modelLength || replaceEnd > modelLength) {
      this.clearActiveSnippetSlot();
      return undefined;
    }
    if (cursor < replaceStart || cursor > replaceEnd) {
      const extendedSlot = versionChanged ? this.tryExtendActiveSnippetSlot(model, replaceStart, cursor) : null;
      if (extendedSlot) {
        return {
          type: extendedSlot.type,
          replaceStart: extendedSlot.replaceStart,
          replaceEnd: extendedSlot.replaceEnd,
        };
      }
      this.clearActiveSnippetSlot();
      return undefined;
    }
    if (versionChanged) {
      if (cursor === replaceStart) {
        this.clearActiveSnippetSlot();
        return undefined;
      }
      const extendedSlot = this.tryExtendActiveSnippetSlot(model, replaceStart, cursor);
      if (!extendedSlot) {
        this.clearActiveSnippetSlot();
        return undefined;
      }
      return {
        type: extendedSlot.type,
        replaceStart: extendedSlot.replaceStart,
        replaceEnd: extendedSlot.replaceEnd,
      };
    }
    return {
      type: this.activeSnippetSlot.type,
      replaceStart,
      replaceEnd,
    };
  }

  private tryExtendActiveSnippetSlot(
    model: monaco.editor.ITextModel,
    replaceStart: number,
    cursor: number,
  ): ISqlCompletionActiveSnippetSlot | null {
    if (!this.activeSnippetSlot || cursor <= replaceStart) {
      return null;
    }
    const start = model.getPositionAt(replaceStart);
    const end = model.getPositionAt(cursor);
    if (start.lineNumber !== end.lineNumber) {
      return null;
    }
    const prefix = model.getValueInRange(new monaco.Range(
      start.lineNumber,
      start.column,
      end.lineNumber,
      end.column,
    ));
    if (!/^[A-Za-z0-9_$]+$/.test(prefix)) {
      return null;
    }
    this.activeSnippetSlot = {
      ...this.activeSnippetSlot,
      modelVersionId: model.getVersionId(),
      replaceEnd: cursor,
    };
    return {
      type: this.activeSnippetSlot.type,
      replaceStart,
      replaceEnd: cursor,
    };
  }

  private clearActiveSnippetSlot(): void {
    this.activeSnippetSlot = null;
    this.preparedTips = null;
  }

  private findEditor(modelUri: string): monaco.editor.ICodeEditor | undefined {
    return monaco.editor.getEditors()
      .find((editor) => editor.getModel()?.uri.toString() === modelUri);
  }

  private toSuggestions(
    originalTips: ITipItemVO[],
    model: monaco.editor.ITextModel,
  ): monaco.languages.CompletionItem[] {
    return (originalTips || []).map((tip, index) =>
      this.constructSuggestion({
        ...tip,
        sortText: this.handleTipsSortText(tip, index),
      }, model),
    );
  }

  private toBackendSuggestions(
    result: BackendCompletionResult,
    model: monaco.editor.ITextModel,
    activeSnippetSlot?: ISqlCompletionActiveSnippetSlot,
  ): monaco.languages.CompletionItem[] {
    return (result.candidates || []).reduce((acc: monaco.languages.CompletionItem[], candidate) => {
      const suggestion = this.constructBackendSuggestion(candidate, model, result, activeSnippetSlot);
      if (suggestion) {
        acc.push(suggestion);
      }
      return acc;
    }, []);
  }

  private getWindowedSqlContext(
    model: monaco.editor.ITextModel,
    position: monaco.Position,
  ): WindowedSqlContext {
    const afterEndLineNumber = Math.min(model.getLineCount(), position.lineNumber + 100);
    return {
      beforeSql: model.getValueInRange({
        startLineNumber: Math.max(1, position.lineNumber - 100),
        startColumn: 1,
        endLineNumber: position.lineNumber,
        endColumn: position.column,
      }),
      afterSql: model.getValueInRange({
        startLineNumber: position.lineNumber,
        startColumn: position.column,
        endLineNumber: afterEndLineNumber,
        endColumn: model.getLineMaxColumn(afterEndLineNumber),
      }),
    };
  }

  private emptyCompletionResult(): monaco.languages.ProviderResult<monaco.languages.CompletionList> {
    return {
      suggestions: [],
      incomplete: false,
    };
  }

  private handleTipsSortText(tip: ITipItemVO, index: number): string {
    return `${SORT_TEXT.TIPS}${SORT_TEXT[tip.type] || SORT_TEXT.COLUMN}${index}${tip.value.padEnd(8, 'a')}`.toLowerCase();
  }

  private isBackendCompletion(model?: monaco.editor.ITextModel): boolean {
    return model ? isBackendCompletionModel(model) : false;
  }

  /**
   * Handle sorting
   */
  private handleSortText(tip: ITipItemVO, index?: number): string {
    const { value } = tip;

    const typeWord = SORT_TEXT[tip.type];

    // Pad value to keep sorting stable.
    // console.log('handleSortText', typeWord, value);
    return `${typeWord}${index ?? ''}${(value || '').padEnd(8, 'a')}`.toLowerCase();
  }

  private handleAllTypeSpecialName(name: string, type: TIP_TYPE): string {
    if (
      [
        TIP_TYPE.DATABASE,
        TIP_TYPE.SCHEMA,
        TIP_TYPE.TABLE,
        TIP_TYPE.FUNCTION,
        TIP_TYPE.VIEW,
        TIP_TYPE.PROCEDURE,
      ].includes(type)
    ) {
      return this.handleSpecialName(name);
    }
    return name;
  }

  /**
   * Handle special database, table, column, function, view, and procedure names
   */
  private handleSpecialName(name: string): string {
    // Wrap name in [] if it starts with a digit or contains characters other than letters, digits, or underscores.
    const regex = /^[^a-zA-Z]|[^a-zA-Z0-9_]/;
    const databaseType = this.dbInfo?.databaseType || DatabaseTypeCode.MYSQL;
    if (regex.test(name)) {
      return quoteSqlCompletionIdentifier(name, databaseType);
    }
    return name;
  }

  /**
   * Register cached completions
   */
  public registerCachedTipsProvider(): void {
    this.disposeProvider(this.cachedTipsProvider);
    this.cachedTipsProvider = monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters,
      provideCompletionItems: async (model, position) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const lineContent = model.getLineContent(position.lineNumber);
        const textUntilPosition = lineContent.substring(0, position.column - 1);
        const triggerChars = new Set(triggerCharacters);
        if (triggerChars.has(textUntilPosition.slice(-1))) {
          return {
            suggestions: [],
            dispose: () => {},
          };
        }
        return {
          suggestions: this.cachedTips,
          incomplete: false,
        };
      },
    });
  }
  /**
   * Register tip completions
   */
  public registerTipsProvider(): void {
    this.disposeProvider(this.tipsProvider);

    const time = new Date().getTime();
    this.tipsProvider = monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters,
      provideCompletionItems: async (model, position) => {
        if (!this.getDBInfoForModel(model)) {
          clearBackendCompletionSuggestWidth();
          return this.emptyCompletionResult();
        }
        const lineContent = model.getLineContent(position.lineNumber);
        const textUntilPosition = lineContent.substring(0, position.column - 1);
        const triggerChars = new Set(triggerCharacters);
        const backendCompletionMode = this.isBackendCompletion(model);
        if (!backendCompletionMode) {
          clearBackendCompletionSuggestWidth();
        }

        const sql = model.getValue();
        const activeSnippetSlot = this.getActiveSnippetSlotForRequest(model, position);
        const cursor = model.getOffsetAt(position);

        const preparedTips = this.consumePreparedTips(model, cursor);
        if (preparedTips) {
          if (backendCompletionMode) {
            applyBackendCompletionSuggestWidth(preparedTips, this.findEditor(model.uri.toString()));
          }
          return {
            suggestions: preparedTips,
            incomplete: false,
          };
        }

        if (!backendCompletionMode && !triggerChars.has(textUntilPosition.slice(-1))) {
          return {
            suggestions: this.cachedTips,
            incomplete: false,
            dispose: () => {},
          };
        }

        // console.log('xx', new Date().getTime() - time);
        if (!backendCompletionMode && new Date().getTime() - time <= 300) {
          return {
            suggestions: this.cachedTips,
          };
        }

        const backendCompletion = backendCompletionMode
          ? await this.fetchBackendCompletion({
              model,
              sql,
              cursor,
              activeSnippetSlot,
            })
          : await this.fetchLegacyBackendCompletion(model, position);
        if (!backendCompletion) {
          if (backendCompletionMode) {
            clearBackendCompletionSuggestWidth();
          }
          return undefined;
        }

        const tips = backendCompletionMode
          ? this.toBackendSuggestions(backendCompletion, model, activeSnippetSlot)
          : this.toSuggestions(
              backendCompletion.candidates.map((candidate) =>
                this.sqlCompletionCandidateToTip(candidate, backendCompletion, activeSnippetSlot),
              ),
              model,
            );
        if (!tips.length) {
          if (backendCompletionMode) {
            clearBackendCompletionSuggestWidth();
          }
          return undefined;
        }
        if (!backendCompletionMode) {
          this.cachedTips = tips.map((tip) => this.withoutCandidateReplacementRange(tip));
        } else {
          applyBackendCompletionSuggestWidth(tips, this.findEditor(model.uri.toString()));
        }

        // console.log('tips', tips);
        return {
          suggestions: tips,
          // tips.length > 0
          //   ? tips
          //   : [
          //       {
          //         label: 'loading....',
          //         kind: monaco.languages.CompletionItemKind.Keyword,
          //         insertText: '',
          //         sortText: 'z',
          //       },
          //     ],
          incomplete: false,
        };
      },
    });
  }

  private getCandidateReplacementRange(
    item: CandidateReplacementOffsets,
    model?: monaco.editor.ITextModel,
    options?: { strict?: boolean },
  ): monaco.Range | undefined {
    if (!model
      || item.replaceStart === undefined
      || item.replaceEnd === undefined
      || !Number.isFinite(item.replaceStart)
      || !Number.isFinite(item.replaceEnd)) {
      return undefined;
    }
    const modelLength = model.getValueLength();
    const startOffset = item.replaceStart;
    const endOffset = item.replaceEnd;
    if (options?.strict && (startOffset < 0 || endOffset < startOffset || endOffset > modelLength)) {
      return undefined;
    }
    const safeStartOffset = Math.max(0, Math.min(startOffset, modelLength));
    const safeEndOffset = Math.max(safeStartOffset, Math.min(endOffset, modelLength));
    const start = model.getPositionAt(safeStartOffset);
    const end = model.getPositionAt(safeEndOffset);
    if (start.lineNumber !== end.lineNumber) {
      return undefined;
    }
    return new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column);
  }

  private hasReplacementOffsets(item?: CandidateReplacementOffsets | null): boolean {
    return item?.replaceStart !== undefined || item?.replaceEnd !== undefined;
  }

  private withoutCandidateReplacementRange(
    suggestion: monaco.languages.CompletionItem,
  ): monaco.languages.CompletionItem {
    if (!suggestion.range) {
      return suggestion;
    }
    const { range, ...rest } = suggestion;
    return rest as monaco.languages.CompletionItem;
  }

  private getKeywordConfig(databaseType?: DatabaseTypeCode): Partial<IKeyword> {
    const configMap = keywordObj as Partial<Record<DatabaseTypeCode, IKeyword>>;
    return (databaseType ? configMap[databaseType] : undefined) || configMap[DatabaseTypeCode.MYSQL] || {};
  }

  /**
   * Register built-in keyword completions
   */
  public registerBuiltInKeywordsProvider(): void {
    if (!this.dbInfo?.databaseType) return;

    const { databaseType } = this.dbInfo;
    this.disposeProvider(this.builtInKeywordsProvider);

    const { keywords = [], priority_keywords = [] } = this.getKeywordConfig(databaseType);
    // const keywordCase = useGlobalStore.getState().editorSettings?.keywordCase;
    const keywordTips: ITipItemVO[] = keywords.map((key: string) => ({
      value: key.toLowerCase(),
      type: TIP_TYPE.KEYWORD,
      dataType: '',
      comment: '',
      datasourceName: '',
      databaseName: '',
      schemaName: '',
      tableName: '',
    }));

    const priorityKeywordTips: ITipItemVO[] = priority_keywords.map((key: string) => ({
      value: key.toLowerCase(),
      type: TIP_TYPE.KEYWORD,
      dataType: '',
      comment: '',
      datasourceName: '',
      databaseName: '',
      schemaName: '',
      tableName: '',
      sortText: `${SORT_TEXT.KEYWORD_HIGH_PRIORITY}_${key}`.toLowerCase(),
    }));

    this.builtInKeywordsProvider = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const keywordCase = useGlobalStore.getState().editorSettings?.keywordCase;

        const tips = priorityKeywordTips
          .map((k) => ({
            ...k,
            value: keywordCase ? k.value.toUpperCase() : k.value.toLowerCase(),
          }))
          .concat(
            [...keywordTips].map((cur: ITipItemVO, index) => ({
              ...cur,
              value: keywordCase ? cur.value.toUpperCase() : cur.value.toLowerCase(),
              sortText: this.handleSortText(cur, index),
            })),
          );

        const suggestions = this.handleKeywordCompletionItems(tips);
        return {
          suggestions,
          incomplete: false,
        };
      },
    });
  }

  /**
   * Register built-in function completions
   */
  public registerBuiltInFunctionsProvider(): void {
    if (!this.dbInfo?.databaseType) return;

    const { databaseType } = this.dbInfo;
    this.disposeProvider(this.builtInFunctionsProvider);
    const { functions = [], priority_functions = [] } = this.getKeywordConfig(databaseType);

    const jointFunctionParams = (parameters: IFunctionParameter[]) => {
      if (!parameters || !parameters?.length) return '';
      return '(' + (parameters || []).map((p) => p.name + ':' + p.type).join(', ') + ')';
    };
    const functionTips: ITipItemVO[] = functions.map(({ name, parameters, returnType, example }) => ({
      value: name,
      type: TIP_TYPE.FUNCTION,
      dataType: '',
      comment: example || '',
      datasourceName: '',
      databaseName: '',
      schemaName: '',
      tableName: '',
      insertText: name + '(${1:})',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      detail: jointFunctionParams(parameters),
      description: returnType,
    }));
    const priorityFunctionTips: ITipItemVO[] = priority_functions.map(({ name, parameters, returnType }) => ({
      value: name,
      type: TIP_TYPE.FUNCTION,
      dataType: '',
      comment: '',
      datasourceName: '',
      databaseName: '',
      schemaName: '',
      tableName: '',
      insertText: name + '(${1:})',
      insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      sortText: `${SORT_TEXT.KEYWORD_HIGH_PRIORITY}_${name}`.toLowerCase(),
      detail: jointFunctionParams(parameters),
      description: returnType,
    }));
    this.builtInFunctionsProvider = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const tips = priorityFunctionTips.concat(
          [...functionTips].map((cur: ITipItemVO) => ({
            ...cur,
            sortText: this.handleSortText(cur),
          })),
        );
        return {
          suggestions: this.filterCachedTips(this.handleFunctionCompletionItem(tips)),
          incomplete: false,
        };
      },
    });
  }

  /**
   * Register database completions
   * @param databases
   */
  public registerDatabaseProvider(databases: ISimpleDatabaseVO[]): void {
    this.disposeProvider(this.databaseProvider);
    const databaseTips: ITipItemVO[] = (databases || []).map(({ databaseName, datasourceName, insertText }) => ({
      value: databaseName,
      insertText,
      type: TIP_TYPE.DATABASE,
      dataType: '',
      comment: '',
      datasourceName,
      databaseName,
      schemaName: '',
      tableName: '',
    }));
    this.databaseProvider = monaco.languages.registerCompletionItemProvider('sql', {
      // triggerCharacters: [' ', '.'],
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const tips = (databaseTips || []).map((cur: ITipItemVO) => ({
          ...cur,
          sortText: this.handleSortText(cur),
        }));

        return {
          suggestions: this.filterCachedTips(this.handleDatabaseCompletionItems(tips)),
          incomplete: false,
        };
      },
    });
  }

  /**
   * Register schema completions
   * @param schemas
   */
  public registerSchemaProvider(schemas: ISimpleSchemaVO[]): void {
    this.disposeProvider(this.schemaProvider);
    const schemaTips: ITipItemVO[] = (schemas || []).map(
      ({ schemaName, databaseName, datasourceName, insertText }) => ({
        value: schemaName,
        insertText,
        type: TIP_TYPE.SCHEMA,
        dataType: '',
        comment: '',
        datasourceName,
        databaseName,
        schemaName,
        tableName: '',
      }),
    );
    this.schemaProvider = monaco.languages.registerCompletionItemProvider('sql', {
      // triggerCharacters: [' ', '.'],
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const tips = (schemaTips || []).map((cur: ITipItemVO) => ({
          ...cur,
          sortText: this.handleSortText(cur),
        }));
        return {
          suggestions: this.filterCachedTips(this.handleSchemaCompletionItems(tips)),
          incomplete: false,
        };
      },
    });
  }

  public registerTableProvider(tables: ISimpleTableVO[]): void {
    this.disposeProvider(this.tableProvider);
    const tableTips: ITipItemVO[] = (tables || []).map(
      ({ tableName, schemaName, databaseName, datasourceName, tableAlias, comment, insertText }) => ({
        value: tableAlias || tableName,
        insertText,
        type: TIP_TYPE.TABLE,
        dataType: '',
        comment,
        datasourceName,
        databaseName,
        schemaName,
        tableName,
      }),
    );
    this.tableProvider = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const tips = (tableTips || []).map((cur: ITipItemVO) => ({
          ...cur,
          sortText: this.handleSortText(cur),
        }));
        // console.log('tableTips', this.handleTableProvider(tips));
        return {
          suggestions: this.filterCachedTips(this.handleTableProvider(tips)),
          incomplete: false,
        };
      },
    });
  }

  public registerParserColumnProvider(columns?: ISimpleColumnVO[]) {
    this.disposeProvider(this.columnProvider);
    this.columnProvider = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const columnTips: ITipItemVO[] = (this.originColumnList || columns || []).map(
          ({ columnName, tableName, databaseName, datasourceName, schemaName, dataType, comment, insertText }) => ({
            value: columnName,
            insertText,
            type: TIP_TYPE.COLUMN,
            dataType,
            comment,
            datasourceName,
            databaseName,
            schemaName,
            tableName,
            columnName,
          }),
        );

        const tips: ITipItemVO[] = (columnTips || []).map((cur: ITipItemVO) => ({
          ...cur,
          sortText: this.handleSortText(cur),
        }));

        // const suggestions = this.filterCachedTips((tips || []).map((tip) => this.constructSuggestion(tip)));
        // console.log('suggestions', tips, suggestions);
        const suggestions = tips.map((tip) => this.constructSuggestion(tip));
        return {
          suggestions,
          incomplete: false,
        };
      },
    });
  }

  public registerViewProvider(views: ISimpleViewVO[]): void {
    this.disposeProvider(this.viewProvider);
    const viewTips: ITipItemVO[] = (views || []).map(
      ({ viewName, databaseName, schemaName, datasourceName, insertText }) => ({
        value: viewName,
        insertText,
        datasourceName,
        databaseName,
        schemaName,
        type: TIP_TYPE.VIEW,
        dataType: '',
        comment: '',
      }),
    );
    this.viewProvider = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const tips: ITipItemVO[] = (viewTips || []).map((cur: ITipItemVO) => ({
          ...cur,
          sortText: this.handleSortText(cur),
        }));
        return {
          suggestions: this.filterCachedTips((tips || []).map((tip) => this.constructSuggestion(tip))),
          incomplete: false,
        };
      },
    });
  }

  public registerFunctionProvider(_functions: ISimpleFunctionVO[]): void {
    this.disposeProvider(this.functionProvider);
    const jointFunctionParams = ({ parameters }: ISimpleFunctionVO) => {
      if (!parameters || !parameters?.length) return '';
      return '(' + (parameters || []).map((p) => p.parameterName + ':' + p.parameterType).join(', ') + ')';
    };
    const functionTips: ITipItemVO[] = (_functions || []).map((_function) => {
      const { functionName, databaseName, schemaName, datasourceName, parameters, returnType } = _function;
      return {
        value: functionName,
        detail: jointFunctionParams(_function),
        description: returnType,
        insertText: functionName + '(${1:})',
        insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
        type: TIP_TYPE.FUNCTION,
        datasourceName,
        databaseName,
        schemaName,
        dataType: '',
        comment: (parameters || []).map((p) => p.parameterName + ':' + p.parameterType).join('\n'),
      };
    });
    this.functionProvider = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const tips: ITipItemVO[] = (functionTips || []).map((cur: ITipItemVO) => ({
          ...cur,
          sortText: this.handleSortText(cur),
        }));
        return {
          suggestions: this.filterCachedTips((tips || []).map((tip) => this.constructSuggestion(tip))),
          incomplete: false,
        };
      },
    });
  }

  public registerProcedureProvider(procedures: ISimpleProcedureVO[]): void {
    this.disposeProvider(this.producerProvider);
    const procedureTips: ITipItemVO[] = (procedures || []).map(
      ({ procedureName, databaseName, schemaName, datasourceName, parameters }) => ({
        value: procedureName,
        type: TIP_TYPE.PROCEDURE,
        dataType: '',
        insertText: procedureName + '(${1:})',
        insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
        datasourceName,
        databaseName,
        schemaName,
        comment: (parameters || []).map((p) => p.parameterName + ':' + p.parameterType).join('\n'),
      }),
    );
    this.producerProvider = monaco.languages.registerCompletionItemProvider('sql', {
      provideCompletionItems: (model) => {
        if (this.isBackendCompletion(model)) {
          return this.emptyCompletionResult();
        }
        const tips: ITipItemVO[] = (procedureTips || []).map((cur: ITipItemVO) => ({
          ...cur,
          sortText: this.handleSortText(cur),
        }));
        return {
          suggestions: this.filterCachedTips((tips || []).map((tip) => this.constructSuggestion(tip))),
          incomplete: false,
        };
      },
    });
  }

  public registerTriggerProvider(provider: monaco.languages.CompletionItemProvider): void {
    this.disposeProvider(this.triggerProvider);
    this.triggerProvider = monaco.languages.registerCompletionItemProvider('sql', provider);
  }
}

export default CompletionProviderManager;
