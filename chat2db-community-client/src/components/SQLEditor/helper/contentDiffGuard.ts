import { clientRuntime } from '@client-runtime';
import { WorkspaceTabType } from '@/constants/workspace';
import {
  EditorType,
  isContentDiffEditableDDLType,
  isContentDiffLocalSQLFileType,
  isContentDiffSavedSQLType,
} from '../type';

type ContentDiffBoundInfo = {
  consoleId?: number | string;
  dataSourceId?: number;
  databaseName?: string;
  schemaName?: string;
  status?: unknown;
  filePath?: string;
  viewName?: string;
  tableName?: string;
  functionName?: string;
  procedureName?: string;
  triggerName?: string;
  eventName?: string;
  readOnly?: boolean;
};

const CONTENT_DIFF_MAX_TEXT_LENGTH = 200000;
const CONTENT_DIFF_MAX_HUNK_COUNT = 500;
const CONTENT_DIFF_MAX_DECORATION_COUNT = 1000;

export enum ContentDiffSourceKind {
  EditableDDL = 'editableDDL',
  SavedSQL = 'savedSQL',
  LocalSQLFile = 'localSQLFile',
}

export enum ContentDiffSurface {
  GutterHints = 'gutterHints',
  InlineViewer = 'inlineViewer',
  DiffTab = 'diffTab',
  Toolbar = 'toolbar',
  ContextMenu = 'contextMenu',
}

export enum ContentDiffDenyReason {
  Disabled = 'disabled',
  Unchanged = 'unchanged',
  UnsupportedType = 'unsupportedType',
  MissingSource = 'missingSource',
  UnsavedSQL = 'unsavedSQL',
  TextTooLarge = 'textTooLarge',
  TooManyHunks = 'tooManyHunks',
  TooManyDecorations = 'tooManyDecorations',
}

export interface ContentDiffEligibility {
  enabled: boolean;
  sourceKind?: ContentDiffSourceKind;
  sourceId?: string;
  reason?: ContentDiffDenyReason;
}

export interface ContentDiffTextGuard {
  enabled: boolean;
  baselineHash: string;
  currentHash: string;
  reason?: ContentDiffDenyReason;
}

const CONTENT_DIFF_DDL_SOURCE_KEYS = [
  'dataSourceId',
  'databaseName',
  'schemaName',
  'viewName',
  'functionName',
  'procedureName',
  'triggerName',
  'eventName',
] as const;

export const shouldEnableContentDiff = (params: {
  editorType: EditorType;
  dbInfo: ContentDiffBoundInfo;
  savedSqlRecord?: boolean;
}) => getContentDiffEligibility(params).enabled;

export const getContentDiffEligibility = (params: {
  editorType: EditorType;
  dbInfo: ContentDiffBoundInfo;
  savedSqlRecord?: boolean;
}): ContentDiffEligibility => {
  const { editorType, dbInfo, savedSqlRecord = false } = params;

  if (isContentDiffEditableDDLType(editorType)) {
    const sourceId = buildContentDiffDDLSourceId(editorType, dbInfo);
    return sourceId ? allow(ContentDiffSourceKind.EditableDDL, sourceId) : deny(ContentDiffDenyReason.MissingSource);
  }

  if (isContentDiffSavedSQLType(editorType)) {
    if (!savedSqlRecord || dbInfo.consoleId === undefined || isTemporaryContentDiffId(dbInfo.consoleId)) {
      return deny(ContentDiffDenyReason.UnsavedSQL);
    }

    return allow(ContentDiffSourceKind.SavedSQL, `console:${dbInfo.consoleId}`);
  }

  if (isContentDiffLocalSQLFileType(editorType)) {
    return dbInfo.filePath
      ? allow(ContentDiffSourceKind.LocalSQLFile, `file:${dbInfo.filePath}`)
      : deny(ContentDiffDenyReason.MissingSource);
  }

  return deny(ContentDiffDenyReason.UnsupportedType);
};

export const isContentDiffSurfaceEnabled = (surface: ContentDiffSurface) =>
  !getContentDiffDisabledSurfaces().has(surface);

export const guardContentDiffTexts = (baselineText: string, currentText: string): ContentDiffTextGuard => {
  const baselineHash = hashContentDiffText(baselineText);
  const currentHash = hashContentDiffText(currentText);

  if (baselineHash === currentHash && baselineText === currentText) {
    return {
      enabled: false,
      baselineHash,
      currentHash,
      reason: ContentDiffDenyReason.Unchanged,
    };
  }

  if (baselineText.length > CONTENT_DIFF_MAX_TEXT_LENGTH || currentText.length > CONTENT_DIFF_MAX_TEXT_LENGTH) {
    return {
      enabled: false,
      baselineHash,
      currentHash,
      reason: ContentDiffDenyReason.TextTooLarge,
    };
  }

  return {
    enabled: true,
    baselineHash,
    currentHash,
  };
};

export const getContentDiffOpenBlockReason = (baselineText: string, currentText: string) => {
  return baselineText.length > CONTENT_DIFF_MAX_TEXT_LENGTH || currentText.length > CONTENT_DIFF_MAX_TEXT_LENGTH
    ? ContentDiffDenyReason.TextTooLarge
    : undefined;
};

export const isContentDiffHunkBudgetExceeded = (params: { hunkCount: number; decorationCount: number }) => {
  return params.hunkCount > CONTENT_DIFF_MAX_HUNK_COUNT || params.decorationCount > CONTENT_DIFF_MAX_DECORATION_COUNT;
};

export const getContentDiffDecorationCount = (
  hunks: Array<{ currentRange: { startLineNumber: number; endLineNumber: number } }>,
) =>
  hunks.reduce((count, hunk) => {
    return count + Math.max(hunk.currentRange.endLineNumber - hunk.currentRange.startLineNumber + 1, 1);
  }, 0);

export const hashContentDiffText = (value: string) => {
  let hash = 0;

  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) | 0;
  }

  return Math.abs(hash).toString(36);
};

const buildContentDiffDDLSourceId = (editorType: EditorType, dbInfo: ContentDiffBoundInfo) => {
  const objectName = getContentDiffDDLObjectName(editorType, dbInfo);

  if (!objectName) {
    return '';
  }

  return CONTENT_DIFF_DDL_SOURCE_KEYS.map((key) => `${key}:${String(dbInfo[key] || '')}`)
    .concat(`type:${editorType}`, `object:${objectName}`)
    .join('|');
};

const getContentDiffDDLObjectName = (editorType: EditorType, dbInfo: ContentDiffBoundInfo) => {
  const ddlObjectNameMap = {
    [WorkspaceTabType.VIEW]: dbInfo.viewName || dbInfo.tableName,
    [WorkspaceTabType.FUNCTION]: dbInfo.functionName,
    [WorkspaceTabType.PROCEDURE]: dbInfo.procedureName,
    [WorkspaceTabType.TRIGGER]: dbInfo.triggerName,
    [WorkspaceTabType.EVENT]: dbInfo.eventName,
  };

  return ddlObjectNameMap[editorType] || '';
};

const getContentDiffDisabledSurfaces = () => {
  try {
    const value =
      typeof window === 'undefined'
        ? ''
        : window.localStorage?.getItem(clientRuntime.contentDiffDisabledSurfacesStorageKey);
    return new Set(
      (value || '')
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean),
    );
  } catch {
    return new Set<string>();
  }
};

const allow = (sourceKind: ContentDiffSourceKind, sourceId: string): ContentDiffEligibility => ({
  enabled: true,
  sourceKind,
  sourceId,
});

const deny = (reason: ContentDiffDenyReason): ContentDiffEligibility => ({
  enabled: false,
  reason,
});

const isTemporaryContentDiffId = (id: string | number) => typeof id === 'string' && id.startsWith('chat2db_temporary');
