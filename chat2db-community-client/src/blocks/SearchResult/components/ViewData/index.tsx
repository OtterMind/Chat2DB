import {
  memo,
  forwardRef,
  useImperativeHandle,
  ForwardedRef,
  useState,
  useMemo,
  useRef,
  useEffect,
} from 'react';
import { useStyles } from './style';
import { Alert, Button, Segmented, Space } from 'antd';
import { ToolbarBtn } from '@chat2db/ui';
import i18n from '@/i18n';
import { v4 as uuidv4 } from 'uuid';
import type { IHandleViewUpdateDataParams } from '@/blocks/SearchResult/components/ResultSetTable/typings';
import type { OperationRecordUtils } from '@/blocks/SearchResult/components/ResultSetTable/hooks/useOperationRecord';
import sqlService from '@/service/sql';
import { IResultCell } from '@/typings/database';
import { downloadLargeCellValue } from '@/utils/file';
import { isDesktop } from '@/utils/env';
import {
  LoadedLargeCellChunk,
  LargeCellViewerMode,
  LARGE_CELL_VIEWER_MODE,
  canSubmitLargeCellEdit,
  decodeLargeCellChunk,
  getLargeCellDownloadFormat,
  getLargeCellImagePreviewBlob,
  getLargeCellLoadedBytes,
  getLargeCellTransferFormat,
  getLargeCellViewerLimit,
  getLargeCellViewerValue,
  getNextLargeCellChunkLimit,
  LARGE_CELL_ERROR_MESSAGE,
  isBinaryDisplayMode,
  isLargeCellTokenExpiredError,
} from './largeCellValue';
import { getLargeCellDisplayMessage, getLargeCellErrorMessage } from './largeCellValueMessage';
import JsonAwareMonacoEditor from './JsonAwareMonacoEditor';
import { getResultFieldAtTableColumn } from '../ResultSetTable/columnState';

interface IProps {
  className?: string;
  onExecuteSuccess?: () => void;
  onClose?: () => void;
}

interface IViewData extends IHandleViewUpdateDataParams {
  canEdit: boolean;
  field?: string;
  rowId?: string | number;
  operationRecordUtils?: OperationRecordUtils;
}

function resolveViewDataLocation(viewData: IViewData) {
  const { tableInstance, row } = viewData;
  const field = viewData.field || getResultFieldAtTableColumn(tableInstance, viewData.col, row);
  const tableCol = field ? tableInstance.getTableIndexByField(field) : viewData.col;
  const record = tableInstance.getRecordByCell(tableCol >= 1 ? tableCol : 1, row);
  return { field, tableCol, record };
}

type LargeValueLoadingAction = 'initial' | 'more' | 'all' | null;

const cloneCellMeta = (cellMeta?: IResultCell) => (cellMeta ? { ...cellMeta } : undefined);

export interface ViewDataRef {
  openPanel: (params: IViewData) => any;
}

const ViewData = forwardRef((_props: IProps, ref: ForwardedRef<ViewDataRef>) => {
  const [viewData, setViewData] = useState<IViewData | null>(null);
  const [editorValue, setEditorContent] = useState('');
  const [isJsonContent, setIsJsonContent] = useState(false);
  const activeViewDataRef = useRef<IViewData | null>(null);
  const editorValueRef = useRef('');
  const [largeValueChunks, setLargeValueChunks] = useState<LoadedLargeCellChunk[]>([]);
  const [largeValueLoading, setLargeValueLoading] = useState(false);
  const [largeValueLoadingAction, setLargeValueLoadingAction] = useState<LargeValueLoadingAction>(null);
  const [largeValueDownloading, setLargeValueDownloading] = useState(false);
  const [largeValueError, setLargeValueError] = useState<string>('');
  const [largeValueExpired, setLargeValueExpired] = useState(false);
  const [viewerMode, setViewerMode] = useState<LargeCellViewerMode>(LARGE_CELL_VIEWER_MODE.TEXT);
  const [imagePreviewSrc, setImagePreviewSrc] = useState('');
  const largeValueRequestVersionRef = useRef(0);
  const activeLargeValueIdRef = useRef<string>('');
  const originalLargeValueRef = useRef<{
    value: string;
    restoreValue: string | null;
    cellMeta?: IResultCell;
  } | null>(null);
  const { styles } = useStyles();

  editorValueRef.current = editorValue;

  const uuid = useMemo(() => {
    return uuidv4();
  }, []);

  useEffect(() => {
    if (viewData) {
      const { cellMeta } = viewData;
      const { field, record } = resolveViewDataLocation(viewData);
      const tableValue = field ? record?.[field] : undefined;
      setLargeValueChunks([]);
      setLargeValueLoading(false);
      setLargeValueLoadingAction(null);
      setLargeValueError('');
      setLargeValueDownloading(false);
      setLargeValueExpired(false);
      setIsJsonContent(false);
      setViewerMode(LARGE_CELL_VIEWER_MODE.TEXT);
      originalLargeValueRef.current = null;
      if (cellMeta?.largeValue) {
        originalLargeValueRef.current = {
          value: cellMeta.value || '',
          restoreValue: tableValue ?? null,
          cellMeta: cloneCellMeta(cellMeta),
        };
        setEditorValue(cellMeta.value || '');
        if (!isDesktop) {
          return;
        }
        if (cellMeta.largeValueId) {
          loadLargeValueChunk(cellMeta.largeValueId, 0, false, undefined, 'initial');
        } else if (cellMeta.unsupportedReason) {
          setLargeValueError(getLargeCellDisplayMessage(cellMeta.unsupportedReason));
        }
        return;
      }
      setEditorValue(tableValue);
    } else {
      activeLargeValueIdRef.current = '';
      originalLargeValueRef.current = null;
      setLargeValueDownloading(false);
      setLargeValueExpired(false);
      largeValueRequestVersionRef.current += 1;
    }
  }, [viewData]);

  useEffect(() => {
    return () => {
      largeValueRequestVersionRef.current += 1;
      activeLargeValueIdRef.current = '';
    };
  }, []);

  const setEditorValue = (value?: string | null) => {
    setEditorContent(value === null || value === undefined ? '' : String(value));
  };

  const latestChunk = largeValueChunks[largeValueChunks.length - 1];
  const largeValueMeta = viewData?.cellMeta;
  const isLargeValue = !!largeValueMeta?.largeValue;
  const canUseLargeValueActions = isLargeValue && isDesktop;
  const displayMode = latestChunk?.displayMode || largeValueMeta?.valueType;
  const editorLimit = getLargeCellViewerLimit(viewerMode, displayMode);
  const loadedSize = getLargeCellLoadedBytes(largeValueChunks);
  const canLoadAll =
    canUseLargeValueActions &&
    !latestChunk?.eof &&
    getNextLargeCellChunkLimit({ loadedSize, editorLimit }) > 0;
  const canShowJsonTools = viewerMode === LARGE_CELL_VIEWER_MODE.TEXT && isJsonContent;
  const showToolbar = canShowJsonTools || canUseLargeValueActions;
  const largeValueViewerValue = useMemo(() => {
    return getLargeCellViewerValue({
      viewerMode,
      chunks: largeValueChunks,
      preview: largeValueMeta?.value,
    });
  }, [viewerMode, displayMode, largeValueChunks, largeValueMeta?.value]);
  useEffect(() => {
    const blob = getLargeCellImagePreviewBlob({
      viewerMode,
      chunks: largeValueChunks,
      eof: latestChunk?.eof,
      contentType: latestChunk?.contentType,
    });
    if (!blob) {
      setImagePreviewSrc('');
      return;
    }
    const objectUrl = URL.createObjectURL(blob);
    setImagePreviewSrc(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [viewerMode, largeValueChunks, latestChunk?.eof, latestChunk?.contentType]);
  const canSubmitEdit = canSubmitLargeCellEdit({
    isLargeValue,
    viewerMode,
    displayMode,
    eof: latestChunk?.eof,
    loadedSize,
    editorLimit,
  });
  const editorCanEdit = !!viewData?.canEdit && canSubmitEdit;
  const hasReachedEditorLimit = canUseLargeValueActions && loadedSize >= editorLimit && !latestChunk?.eof;
  const largeValueStatus = useMemo(() => {
    const items = [
      displayMode ? String(displayMode) : '',
      i18n('common.largeCellValue.status.loadedBytes', loadedSize),
      latestChunk?.eof
        ? i18n('common.largeCellValue.status.complete')
        : i18n('common.largeCellValue.status.partial'),
    ];
    if (hasReachedEditorLimit) {
      items.push(i18n('common.largeCellValue.status.editorLimitReached'));
    }
    return items.filter(Boolean).join(' · ');
  }, [displayMode, loadedSize, latestChunk?.eof, hasReachedEditorLimit]);

  useEffect(() => {
    if (!isLargeValue || viewerMode === LARGE_CELL_VIEWER_MODE.IMAGE) {
      return;
    }
    setEditorValue(largeValueViewerValue);
  }, [isLargeValue, viewerMode, largeValueViewerValue]);

  useEffect(() => {
    if (
      !isLargeValue ||
      viewerMode !== LARGE_CELL_VIEWER_MODE.TEXT ||
      isBinaryDisplayMode(displayMode) ||
      !latestChunk?.eof ||
      !viewData
    ) {
      return;
    }
    originalLargeValueRef.current = {
      value: largeValueViewerValue,
      restoreValue: (() => {
        const { field, record } = resolveViewDataLocation(viewData);
        return field ? record?.[field] ?? null : null;
      })(),
      cellMeta: cloneCellMeta(largeValueMeta),
    };
  }, [isLargeValue, viewerMode, displayMode, latestChunk?.eof, largeValueViewerValue, largeValueMeta, viewData]);

  const loadLargeValueChunk = async (
    largeValueId: string,
    offset: number,
    append = true,
    limit?: number,
    loadingAction: LargeValueLoadingAction = 'initial',
  ) => {
    const requestVersion = largeValueRequestVersionRef.current;
    setLargeValueLoading(true);
    setLargeValueLoadingAction(loadingAction);
    setLargeValueError('');
    try {
      const chunk = await sqlService.getLargeCellValue({
        largeValueId,
        offset,
        limit,
        format: getLargeCellTransferFormat(),
      });
      if (requestVersion !== largeValueRequestVersionRef.current || activeLargeValueIdRef.current !== largeValueId) {
        return;
      }
      const loadedChunk = decodeLargeCellChunk(chunk);
      setLargeValueChunks((prev) => (append ? [...prev, loadedChunk] : [loadedChunk]));
    } catch (error: any) {
      if (requestVersion !== largeValueRequestVersionRef.current || activeLargeValueIdRef.current !== largeValueId) {
        return;
      }
      setLargeValueExpired(isLargeCellTokenExpiredError(error));
      setLargeValueError(getLargeCellErrorMessage(error, LARGE_CELL_ERROR_MESSAGE.LOAD_FAILED));
    } finally {
      if (requestVersion === largeValueRequestVersionRef.current && activeLargeValueIdRef.current === largeValueId) {
        setLargeValueLoading(false);
        setLargeValueLoadingAction(null);
      }
    }
  };

  const loadMore = () => {
    if (!canUseLargeValueActions || largeValueExpired || !largeValueMeta?.largeValueId) {
      return;
    }
    loadLargeValueChunk(largeValueMeta.largeValueId, latestChunk?.nextOffset || 0, true, undefined, 'more');
  };

  const loadAllUpToLimit = async () => {
    if (!canUseLargeValueActions || largeValueExpired || !largeValueMeta?.largeValueId) {
      return;
    }
    const largeValueId = largeValueMeta.largeValueId;
    const requestVersion = largeValueRequestVersionRef.current;
    let offset = latestChunk?.nextOffset || 0;
    let eof = !!latestChunk?.eof;
    let nextLoadedSize = loadedSize;
    let nextLimit = getNextLargeCellChunkLimit({ loadedSize: nextLoadedSize, editorLimit });
    const loadedChunks: LoadedLargeCellChunk[] = [];
    setLargeValueLoading(true);
    setLargeValueLoadingAction('all');
    setLargeValueError('');
    try {
      while (!eof && nextLimit > 0) {
        const chunk = await sqlService.getLargeCellValue({
          largeValueId,
          offset,
          limit: nextLimit,
          format: getLargeCellTransferFormat(),
        });
        if (requestVersion !== largeValueRequestVersionRef.current || activeLargeValueIdRef.current !== largeValueId) {
          break;
        }
        loadedChunks.push(decodeLargeCellChunk(chunk));
        eof = chunk.eof;
        nextLoadedSize += chunk.nextOffset - chunk.offset;
        offset = chunk.nextOffset;
        nextLimit = getNextLargeCellChunkLimit({ loadedSize: nextLoadedSize, editorLimit });
      }
    } catch (error: any) {
      if (requestVersion === largeValueRequestVersionRef.current && activeLargeValueIdRef.current === largeValueId) {
        setLargeValueExpired(isLargeCellTokenExpiredError(error));
        setLargeValueError(getLargeCellErrorMessage(error, LARGE_CELL_ERROR_MESSAGE.LOAD_FAILED));
      }
    } finally {
      if (requestVersion === largeValueRequestVersionRef.current && activeLargeValueIdRef.current === largeValueId) {
        if (loadedChunks.length) {
          setLargeValueChunks((prev) => [...prev, ...loadedChunks]);
        }
        setLargeValueLoading(false);
        setLargeValueLoadingAction(null);
      }
    }
  };

  const download = async () => {
    if (!canUseLargeValueActions || largeValueExpired || !largeValueMeta?.largeValueId || largeValueDownloading) {
      return;
    }
    const largeValueId = largeValueMeta.largeValueId;
    const requestVersion = largeValueRequestVersionRef.current;
    setLargeValueDownloading(true);
    try {
      await downloadLargeCellValue(largeValueId, getLargeCellDownloadFormat(displayMode));
    } catch (error: any) {
      if (requestVersion === largeValueRequestVersionRef.current && activeLargeValueIdRef.current === largeValueId) {
        setLargeValueExpired(isLargeCellTokenExpiredError(error));
        setLargeValueError(getLargeCellErrorMessage(error, LARGE_CELL_ERROR_MESSAGE.DOWNLOAD_FAILED));
      }
    } finally {
      if (requestVersion === largeValueRequestVersionRef.current && activeLargeValueIdRef.current === largeValueId) {
        setLargeValueDownloading(false);
      }
    }
  };

  const changeViewerMode = (value: LargeCellViewerMode) => {
    setIsJsonContent(false);
    setViewerMode(value);
  };

  function applyEditorValue(data: string) {
    if (!viewData || !editorCanEdit) {
      return;
    }
    const { row, tableInstance, operationRecordUtils } = viewData;
    const { field, tableCol, record: originData } = resolveViewDataLocation(viewData);
    if (!originData || !field) {
      return;
    }
    if (viewData.rowId !== undefined && String(originData.CHAT2DB_ROW_NUMBER) !== String(viewData.rowId)) {
      return;
    }
    const originalLargeValue = originalLargeValueRef.current;
    const currentTableValue = originData[field];
    if (!originalLargeValue && currentTableValue === data) {
      return;
    }
    const currentValue = originalLargeValue?.value ?? currentTableValue;
    const restoreValue = originalLargeValue?.restoreValue;
    const restoreCellMeta = cloneCellMeta(originalLargeValue?.cellMeta);
    const hasChanged = currentValue !== data;
    const nextTableValue = hasChanged ? data : (restoreValue ?? data);

    const sourceIndex = Number(field);
    originData[field] = nextTableValue;
    const nextCellMeta = originData.__CHAT2DB_CELL_META__?.[sourceIndex];
    if (hasChanged && nextCellMeta) {
      nextCellMeta.value = data;
      if (nextCellMeta.largeValue) {
        nextCellMeta.largeValue = false;
        nextCellMeta.largeValueId = undefined;
        nextCellMeta.loadedBytes = undefined;
        nextCellMeta.loadedChars = undefined;
        nextCellMeta.truncated = false;
        nextCellMeta.unsupportedReason = undefined;
      }
    } else if (!hasChanged && restoreCellMeta && originData.__CHAT2DB_CELL_META__) {
      originData.__CHAT2DB_CELL_META__[sourceIndex] = restoreCellMeta;
    }

    if (tableCol >= 1) {
      tableInstance.changeCellValue(tableCol, row, nextTableValue);
    } else {
      tableInstance.render();
    }
    operationRecordUtils?.handleCellValueChange({
      field,
      rowId: originData.CHAT2DB_ROW_NUMBER,
      rawValue: currentValue,
      currentValue,
      changedValue: data,
      restoreValue,
      restoreCellMeta,
    });
  }

  const handleEditorValueChange = (value: string) => {
    setEditorContent(value);
    applyEditorValue(value);
  };

  const formatJson = () => {
    try {
      const parsed = JSON.parse(editorValue);
      const formatted = JSON.stringify(parsed, null, 2);
      handleEditorValueChange(formatted);
    } catch (err) {
      console.error('无效的 JSON 格式，请检查语法', err);
    }
  };

  const compressJson = () => {
    try {
      const parsed = JSON.parse(editorValue);
      const compressed = JSON.stringify(parsed);
      handleEditorValueChange(compressed);
    } catch (err) {
      console.error('无效的 JSON 格式，请检查语法', err);
    }
  };

  const renderMonacoEditor = useMemo(() => {
    return (
      <div className={styles.monacoEditor}>
        {showToolbar && (
          <div className={styles.toolbar}>
            <Space size={8}>
              {canShowJsonTools && (
                <>
                  <ToolbarBtn text={i18n('workspace.format.json')} onClick={formatJson} />
                  <ToolbarBtn text={i18n('workspace.format.json.compress')} onClick={compressJson} />
                </>
              )}
              {canUseLargeValueActions && (
                <Segmented
                  size="small"
                  value={viewerMode}
                  options={[
                    {
                      label: i18n('common.largeCellValue.viewer.text'),
                      value: LARGE_CELL_VIEWER_MODE.TEXT,
                    },
                    {
                      label: i18n('common.largeCellValue.viewer.hex'),
                      value: LARGE_CELL_VIEWER_MODE.HEX,
                    },
                    {
                      label: i18n('common.largeCellValue.viewer.image'),
                      value: LARGE_CELL_VIEWER_MODE.IMAGE,
                    },
                  ]}
                  onChange={(value) => changeViewerMode(value as LargeCellViewerMode)}
                />
              )}
            </Space>
            {canUseLargeValueActions && (
              <Space size={8}>
                <Button
                  size="small"
                  loading={largeValueLoadingAction === 'initial' || largeValueLoadingAction === 'more'}
                  disabled={
                    largeValueExpired || largeValueLoading || !!latestChunk?.eof || !largeValueMeta?.largeValueId
                  }
                  onClick={loadMore}
                >
                  {i18n('common.largeCellValue.button.loadMore')}
                </Button>
                <Button
                  size="small"
                  loading={largeValueLoadingAction === 'all'}
                  disabled={largeValueExpired || largeValueLoading || !canLoadAll || !largeValueMeta?.largeValueId}
                  onClick={loadAllUpToLimit}
                >
                  {i18n('common.largeCellValue.button.loadAllUpToLimit')}
                </Button>
                <Button
                  size="small"
                  loading={largeValueDownloading}
                  disabled={
                    largeValueExpired || largeValueLoading || largeValueDownloading || !largeValueMeta?.largeValueId
                  }
                  onClick={download}
                >
                  {i18n('common.largeCellValue.button.download')}
                </Button>
              </Space>
            )}
          </div>
        )}
        {largeValueError && <Alert className={styles.alert} type="warning" showIcon message={largeValueError} />}
        {canUseLargeValueActions && (
          <div className={styles.meta}>
            {largeValueStatus}
          </div>
        )}
        {hasReachedEditorLimit && (
          <Alert
            className={styles.alert}
            type="info"
            showIcon
            message={i18n('common.largeCellValue.tip.editorLimit')}
          />
        )}
        {viewerMode === LARGE_CELL_VIEWER_MODE.IMAGE && (
          <div className={styles.imagePreview}>
            {imagePreviewSrc && (
              <img src={imagePreviewSrc} alt={viewData?.field || i18n('common.largeCellValue.imageAlt')} />
            )}
          </div>
        )}
        {viewerMode !== LARGE_CELL_VIEWER_MODE.IMAGE && (
          <div className={styles.editorContainer}>
            <JsonAwareMonacoEditor
              id={uuid}
              value={editorValue}
              readOnly={!editorCanEdit}
              onChange={handleEditorValueChange}
              onJsonChange={setIsJsonContent}
            />
          </div>
        )}
      </div>
    );
  }, [
    isLargeValue,
    canUseLargeValueActions,
    canShowJsonTools,
    latestChunk,
    largeValueMeta,
    largeValueLoading,
    largeValueLoadingAction,
    largeValueDownloading,
    largeValueExpired,
    largeValueError,
    loadedSize,
    largeValueStatus,
    displayMode,
    editorValue,
    editorCanEdit,
    isJsonContent,
    viewerMode,
    canLoadAll,
    imagePreviewSrc,
    canSubmitEdit,
    hasReachedEditorLimit,
    showToolbar,
  ]);

  const openPanel = (params) => {
    if (!params) return;
    const { tableInstance } = params;
    const field = params.field || getResultFieldAtTableColumn(tableInstance, params.col, params.row);
    if (!field) {
      return;
    }
    const tableCol = tableInstance.getTableIndexByField(field);
    const record = tableInstance.getRecordByCell(tableCol >= 1 ? tableCol : 1, params.row);
    const cellMeta = params.cellMeta ?? record?.__CHAT2DB_CELL_META__?.[Number(field)];
    const nextViewData = { ...params, col: tableCol, field, cellMeta } as IViewData;
    nextViewData.rowId = params.rowId ?? record?.CHAT2DB_ROW_NUMBER;
    const current = activeViewDataRef.current;
    const isSameCell =
      current?.tableInstance === tableInstance &&
      current.field === field &&
      current.row === params.row &&
      String(current.rowId) === String(nextViewData.rowId);
    const nextValue = cellMeta?.largeValue ? cellMeta.value || '' : record?.[field];
    const nextEditorValue = nextValue === null || nextValue === undefined ? '' : String(nextValue);
    if (isSameCell && nextEditorValue === editorValueRef.current) {
      return;
    }
    if (!isSameCell) {
      largeValueRequestVersionRef.current += 1;
      activeLargeValueIdRef.current = cellMeta?.largeValueId || '';
    }
    activeViewDataRef.current = nextViewData;
    setViewData(nextViewData);
  };

  useImperativeHandle(
    ref,
    () => ({
      openPanel,
    }),
    [],
  );

  if (!viewData) {
    return null;
  }

  return (
    <div className={styles.container}>
      {renderMonacoEditor}
    </div>
  );
});

export default memo(ViewData);
