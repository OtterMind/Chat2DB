import { useGlobalStore } from '@/store/global';
import { IManageResultData, IResultCell, ITableHeaderItem } from '@/typings/database';
import { Theme } from 'antd-style';
import i18n from '@/i18n';
import { resolveResultSetEditor } from './editorType';
import type { HeaderMetadataVisibility } from '../headerMetadata';
import { createResultHeaderCustomRender } from '../headerRender';
import { getCollapsedResultCellPreview, isResultTableRowExpanded } from '../rowHeight';

const handleDataDisplay = (params: {
  data: ITableHeaderItem;
  index: number;
  theme: Omit<Theme, 'prefixCls'>;
  canEdit?: boolean;
  visibility?: HeaderMetadataVisibility;
  hiddenFields?: ReadonlySet<string>;
  readOnlyFields?: ReadonlySet<string>;
}) => {
  const { data, index, theme, canEdit = false, visibility, hiddenFields, readOnlyFields } = params;
  const field = index.toString();
  const customFontSize = useGlobalStore.getState().baseSetting.customFontSize ?? 13;
  const headerCustomRender = createResultHeaderCustomRender({
    data,
    theme,
    fontSize: customFontSize,
    visibility,
  });
  return {
    CHAT2DB_COL_NUMBER: index,
    field,
    hide: hiddenFields?.has(field) ?? false,
    title: '',
    headerCustomRender: (args: { rect?: { width?: number } }) =>
      createResultHeaderCustomRender({
        data,
        theme,
        fontSize: customFontSize,
        visibility,
        availableWidth: args.rect?.width,
      }),
    headerStyle: {
      padding: [
        8,
        8,
        headerCustomRender.expectedHeight - (customFontSize + 9) - 8,
        8,
      ],
    },
    showSort: false,
    editor:
      canEdit && !readOnlyFields?.has(field)
        ? resolveResultSetEditor(data.editorType, data.editorOptions, theme)
        : undefined,
    headerIcon: ['filter', 'sort'],
    sort: (a, b, _order): 0 | 1 | -1 => {
      if (a === null || a === undefined) return _order === 'asc' ? -1 : 1;
      if (b === null || b === undefined) return _order === 'asc' ? 1 : -1;
      if (!isNaN(a) && !isNaN(b)) {
        const result = _order === 'asc' ? a - b : b - a;
        return result > 0 ? 1 : result < 0 ? -1 : 0;
      }
      if (typeof a === 'string' && typeof b === 'string') {
        const result = _order === 'asc' ? a.localeCompare(b) : b.localeCompare(a);
        return result > 0 ? 1 : result < 0 ? -1 : 0;
      }
      return 0;
    },
    customRender: (args) => {
      const cellMeta: IResultCell | undefined = args?.originData?.__CHAT2DB_CELL_META__?.[index];
      if (cellMeta?.largeValue) {
        return {
          elements: [
            {
              type: 'text',
              fill: theme.colorWarningText,
              fontSize: customFontSize,
              fontFamily: theme.fontFamily,
              fontWeight: 600,
              text: i18n('common.largeCellValue.label.large'),
              x: 6,
              y: 19,
            },
            {
              type: 'text',
              fill: theme.colorText,
              fontSize: customFontSize,
              fontFamily: theme.fontFamily,
              fontWeight: 400,
              text: ` ${cellMeta.value || args.dataValue || ''}`,
              x: 54,
              y: 19,
            },
          ],
          expectedHeight: 28,
          expectedWidth: 160,
        };
      }
      if (args.dataValue === null) {
        return {
          elements: [
            {
              type: 'text',
              fill: theme.colorTextSecondary,
              fontSize: customFontSize,
              fontFamily: theme.fontFamily,
              fontWeight: 400,
              text: '<null>',
              x: 6,
              y: 19,
            },
          ],
          expectedHeight: 28,
          expectedWidth: 40,
        };
      } else if (args.dataValue === 'CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_DEFAULT') {
        return {
          elements: [
            {
              type: 'text',
              fill: theme.colorTextSecondary,
              fontSize: customFontSize,
              fontFamily: theme.fontFamily,
              fontWeight: 400,
              text: '<default>',
              x: 6,
              y: 19,
            },
          ],
          expectedHeight: 28,
          expectedWidth: 80,
        };
      } else if (args.dataValue === 'CHAT2DB_UPDATE_TABLE_DATA_USER_FILLED_GENERATED') {
        return {
          elements: [
            {
              type: 'text',
              fill: theme.colorTextSecondary,
              fontSize: customFontSize,
              fontFamily: theme.fontFamily,
              fontWeight: 400,
              text: '<generated>',
              x: 6,
              y: 19,
            },
          ],
          expectedHeight: 28,
          expectedWidth: 90,
        };
      }
      const collapsedPreview = getCollapsedResultCellPreview(
        args.dataValue,
        isResultTableRowExpanded(args.table, args.row),
      );
      if (collapsedPreview !== undefined) {
        return {
          elements: [
            {
              type: 'text',
              fill: theme.colorText,
              fontSize: customFontSize,
              fontFamily: theme.fontFamily,
              fontWeight: 400,
              text: collapsedPreview,
              x: 6,
              y: 19,
              maxLineWidth: Math.max(0, (args.rect?.width ?? 160) - 12),
              heightLimit: 20,
              lineClamp: 1,
              ellipsis: true,
            },
          ],
          expectedHeight: 28,
          expectedWidth: 160,
        };
      }
      return {
        renderDefault: true,
      };
    },
    originalData: data,
  };
};

// Convert data into the format required by CanvasTable.
export const buildResultColumns = (params: {
  data: IManageResultData;
  theme: Omit<Theme, 'prefixCls'>;
  visibility?: HeaderMetadataVisibility;
  hiddenFields?: ReadonlySet<string>;
  readOnlyFields?: ReadonlySet<string>;
}) => {
  const { data, theme, visibility, hiddenFields, readOnlyFields } = params;
  return (
    data?.headerList?.slice(1).map((item, index) => {
      return handleDataDisplay({
        data: item,
        index: index + 1,
        theme,
        canEdit: data.canEdit,
        visibility,
        hiddenFields,
        readOnlyFields,
      });
    }) || []
  );
};

export const buildResultRecords = (data: IManageResultData) => {
  return (
    data?.dataList?.map((item, rowIndex) => {
      const record = {};
      data?.headerList?.forEach((header, index) => {
        const cell = item[index];
        if (index === 0) {
          record['CHAT2DB_ROW_NUMBER'] = cell?.value ?? null;
          return;
        }
        record[index] = cell?.value ?? null;
      });
      record['__CHAT2DB_CELL_META__'] = data?.dataList?.[rowIndex] || [];
      return record;
    }) || []
  );
};

const dataTreating = (params: {
  data: IManageResultData;
  theme: Omit<Theme, 'prefixCls'>;
  visibility?: HeaderMetadataVisibility;
  hiddenFields?: ReadonlySet<string>;
  readOnlyFields?: ReadonlySet<string>;
}) => {
  const columns = buildResultColumns(params);
  const records = buildResultRecords(params.data);

  return [columns, records];
};

export default dataTreating;
