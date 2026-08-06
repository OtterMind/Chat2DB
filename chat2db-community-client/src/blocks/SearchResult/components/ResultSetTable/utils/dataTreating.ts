import { useGlobalStore } from '@/store/global';
import { IManageResultData, IResultCell, ITableHeaderItem } from '@/typings/database';
import { Theme } from 'antd-style';
import i18n from '@/i18n';
import { resolveResultSetEditor } from './editorType';
import type { HeaderMetadataVisibility } from '../headerMetadata';
import { createResultHeaderCustomRender } from '../headerRender';

const handleDataDisplay = (params: {
  data: ITableHeaderItem;
  index: number;
  theme: Omit<Theme, 'prefixCls'>;
  canEdit?: boolean;
  visibility?: HeaderMetadataVisibility;
}) => {
  const { data, index, theme, canEdit = false, visibility } = params;
  const customFontSize = useGlobalStore.getState().baseSetting.customFontSize ?? 13;
  const headerCustomRender = createResultHeaderCustomRender({
    data,
    theme,
    fontSize: customFontSize,
    visibility,
  });
  return {
    CHAT2DB_COL_NUMBER: index,
    field: index.toString(),
    title: '',
    headerCustomRender,
    headerStyle: {
      padding: [
        8,
        8,
        headerCustomRender.expectedHeight - (customFontSize + 9) - 8,
        8,
      ],
    },
    showSort: false,
    editor: canEdit ? resolveResultSetEditor(data.editorType) : undefined,
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
      return {
        renderDefault: true,
      };
    },
    originalData: data,
  };
};

// Convert data into the format required by CanvasTable.
const dataTreating = (params: {
  data: IManageResultData;
  theme: Omit<Theme, 'prefixCls'>;
  visibility?: HeaderMetadataVisibility;
}) => {
  const { data, theme, visibility } = params;
  const columns: any =
    data?.headerList?.slice(1).map((item, index) => {
      return handleDataDisplay({ data: item, index: index + 1, theme, canEdit: data.canEdit, visibility });
    }) || [];

  const records =
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
    }) || [];

  return [columns, records];
};

export default dataTreating;
