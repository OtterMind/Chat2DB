import { isSelected } from '@/blocks/CanvasTable/utils';
import { IOnContextmenuEvent } from '../../typings';
import { ContextmenuType } from '../../constants';
import handlePaste from './handlePaste';
import handleCopy from './handleCopy';
import handleViewUpdateData from './handleViewUpdateData';
import handleSetNull from './handleSetNull';
import handleCopyRow from './handleCopyRow';
import handleCopyAsSqlInValues from './handleCopyAsSqlInValues';
import handleCopyAsMarkdown from './handleCopyAsMarkdown';
import i18n from '@/i18n';
import { copyToClipboard } from '@/utils';
import { downloadLargeCellValue } from '@/utils/file';
import { isDesktop } from '@/utils/env';
import feedback from '@/utils/feedback';
import {
  getLargeCellDownloadFormat,
  LARGE_CELL_ERROR_MESSAGE,
} from '@/blocks/SearchResult/components/ViewData/largeCellValue';
import { getLargeCellErrorMessage } from '@/blocks/SearchResult/components/ViewData/largeCellValueMessage';

import { staticMessage } from '@chat2db/ui';

// monitors the right mouse click on a cell
const onContextmenuCell = (props: IOnContextmenuEvent) => {
  const { resultData, tableInstance, operationRecordUtils, onTableOperationUtils } = props;
  const id = tableInstance.on('contextmenu_cell', (selectEvent) => {
    const contextmenuMap = {
      [ContextmenuType.viewUpdateData]: {
        key: ContextmenuType.viewUpdateData,
        label: i18n('common.button.viewData'),
        icon: 'icon-view-data',
        onClick: () => {
          const data = handleViewUpdateData(tableInstance, selectEvent);
          onTableOperationUtils.handleViewUpdateData?.(data);
        },
      },
      [ContextmenuType.viewRowDetail]: {
        key: ContextmenuType.viewRowDetail,
        label: i18n('common.button.viewRowDetail'),
        icon: 'icon-view-data',
        onClick: () => {
          onTableOperationUtils.handleViewRowDetail?.({
            tableInstance,
            col: selectEvent.col,
            row: selectEvent.row,
          });
        },
      },
      [ContextmenuType.copy]: {
        key: ContextmenuType.copy,
        label: i18n('common.button.copy'),
        icon: 'icon-copy',
        onClick: () => {
          handleCopy(tableInstance);
        },
      },
      [ContextmenuType.viewFullValue]: {
        key: ContextmenuType.viewFullValue,
        label: i18n('common.button.viewFullValue'),
        icon: 'icon-view-data',
        onClick: () => {
          const data = handleViewUpdateData(tableInstance, selectEvent);
          onTableOperationUtils.handleViewUpdateData?.(data);
        },
      },
      [ContextmenuType.copyPreview]: {
        key: ContextmenuType.copyPreview,
        label: i18n('common.largeCellValue.button.copyPreview'),
        icon: 'icon-copy',
        onClick: () => {
          const record = tableInstance.getRecordByCell(selectEvent.col, selectEvent.row);
          const cellMeta = record?.__CHAT2DB_CELL_META__?.[selectEvent.col];
          copyToClipboard(cellMeta?.value || selectEvent.dataValue || '');
        },
      },
      [ContextmenuType.saveToFile]: {
        key: ContextmenuType.saveToFile,
        label: i18n('common.largeCellValue.button.saveToFile'),
        icon: 'icon-download',
        onClick: async () => {
          const record = tableInstance.getRecordByCell(selectEvent.col, selectEvent.row);
          const cellMeta = record?.__CHAT2DB_CELL_META__?.[selectEvent.col];
          if (cellMeta?.largeValueId) {
            try {
              await downloadLargeCellValue(cellMeta.largeValueId, getLargeCellDownloadFormat(cellMeta.valueType));
            } catch (error: any) {
              feedback.warning(getLargeCellErrorMessage(error, LARGE_CELL_ERROR_MESSAGE.DOWNLOAD_FAILED));
            }
          }
        },
      },
      [ContextmenuType.copyFieldName]: {
        key: ContextmenuType.copyFieldName,
        label: i18n('workspace.menu.copyColumnName'),
        icon: 'icon-copy',
        onClick: () => {
          handleCopy(tableInstance, selectEvent.dataValue);
        },
      },
      [ContextmenuType.paste]: {
        key: ContextmenuType.paste,
        label: i18n('common.button.paste'),
        icon: 'icon-paste',
        onClick: () => {
          handlePaste(tableInstance, operationRecordUtils);
        },
      },
      [ContextmenuType.setNull]: {
        key: ContextmenuType.setNull,
        label: i18n('common.button.setNull'),
        // unreal icon used as placeholder
        icon: 'icon-chat2db-unreal',
        onClick: () => {
          handleSetNull(tableInstance);
        },
      },
      [ContextmenuType.cloneRow]: {
        key: ContextmenuType.cloneRow,
        label: i18n('common.button.cloneRow'),
        // unreal icon used as placeholder
        icon: 'icon-chat2db-unreal',
        onClick: () => {
          operationRecordUtils.handleCloneRow();
        },
      },
      [ContextmenuType.deleteRow]: {
        key: ContextmenuType.deleteRow,
        label: i18n('common.button.deleteRow'),
        icon: 'icon-trash',
        onClick: () => {
          operationRecordUtils.handleDeleteRow();
        },
      },
      [ContextmenuType.copyRow]: {
        key: ContextmenuType.copyRow,
        label: i18n('common.button.copyRowAs'),
        icon: 'icon-copy',
        children: [
          {
            key: ContextmenuType.copyRowInsert,
            label: i18n('common.button.insertSql'),
            onClick: () => {
              const operations = handleCopyRow({ tableInstance, selectEvent, type: ContextmenuType.copyRowInsert });
              onTableOperationUtils.copyGenerateSQL?.(operations);
            },
          },
          {
            key: ContextmenuType.copyRowUpdate,
            label: i18n('common.button.updateSql'),
            onClick: () => {
              const operations = handleCopyRow({ tableInstance, selectEvent, type: ContextmenuType.copyRowUpdate });
              onTableOperationUtils.copyGenerateSQL?.(operations);
            },
          },
          {
            key: ContextmenuType.copyRowWhere,
            label: i18n('common.button.whereSql'),
            onClick: () => {
              const operations = handleCopyRow({ tableInstance, selectEvent, type: ContextmenuType.copyRowWhere });
              onTableOperationUtils.copyGenerateSQL?.(operations);
            },
          },
          {
            key: ContextmenuType.copyAsSqlInValues,
            label: i18n('common.button.copyAsSqlInValues'),
            onClick: () => {
              const { operations, errorKey } = handleCopyAsSqlInValues({ tableInstance });
              if (errorKey) {
                staticMessage.warning(i18n(errorKey));
                return;
              }
              onTableOperationUtils.copyGenerateInValues?.(operations);
            },
          },
          {
            key: ContextmenuType.tabSplit,
            label: i18n('common.button.tabularSeparatedValues'),
            onClick: () => {
              handleCopyRow({ tableInstance, selectEvent, type: ContextmenuType.tabSplit });
            },
          },
          {
            key: ContextmenuType.tabSplitField,
            label: i18n('common.button.tabularSeparatedValuesFieldName'),
            onClick: () => {
              handleCopyRow({ tableInstance, selectEvent, type: ContextmenuType.tabSplitField });
            },
          },
          {
            key: ContextmenuType.tabSplitFieldAndValue,
            label: i18n('common.button.tabularSeparatedValuesFieldNameAndData'),
            onClick: () => {
              handleCopyRow({ tableInstance, selectEvent, type: ContextmenuType.tabSplitFieldAndValue });
            },
          },
          {
            key: ContextmenuType.markdownTable,
            label: i18n('common.button.markdownTable'),
            onClick: () => {
              handleCopyAsMarkdown(tableInstance);
            },
          },
        ],
      },
    };

    // If the cell I right-click is within the selected range, I don’t need to select it again.
    if (!isSelected(selectEvent, tableInstance.getSelectedCellInfos() || [])) {
      tableInstance.selectCell(selectEvent.col, selectEvent.row);
    }

    // right-click menu
    let dropdownsList: any = [
      contextmenuMap[ContextmenuType.copy],
      contextmenuMap[ContextmenuType.paste],
      contextmenuMap[ContextmenuType.copyRow],
    ];

    // Case1: Right-click content at [0,0] position
    // if (selectEvent.row === 0 && selectEvent.col === 0) {
    //   dropdownsList = [
    //     contextmenuMap[ContextmenuType.copy],
    //     contextmenuMap[ContextmenuType.paste],
    //   ]
    // }

    // Case2: Meter head
    if (selectEvent.row === 0 && selectEvent.col > 0) {
      dropdownsList = [
        contextmenuMap[ContextmenuType.copy],
        contextmenuMap[ContextmenuType.copyFieldName],
        contextmenuMap[ContextmenuType.paste],
      ];
    }

    // Case3: Line number
    if (selectEvent.row > 0 && selectEvent.col === 0) {
      dropdownsList = [
        contextmenuMap[ContextmenuType.viewRowDetail],
        contextmenuMap[ContextmenuType.copy],
        contextmenuMap[ContextmenuType.paste],
        contextmenuMap[ContextmenuType.cloneRow],
        contextmenuMap[ContextmenuType.deleteRow],
        contextmenuMap[ContextmenuType.copyRow],
      ];
    }

    // Case4: Data cells in the table body
    if (selectEvent.row > 0 && selectEvent.col > 0) {
      const record = tableInstance.getRecordByCell(selectEvent.col, selectEvent.row);
      const cellMeta = record?.__CHAT2DB_CELL_META__?.[selectEvent.col];
      dropdownsList = [
        ...(cellMeta?.largeValue
          ? [
              contextmenuMap[ContextmenuType.viewRowDetail],
              ...(isDesktop ? [contextmenuMap[ContextmenuType.viewFullValue]] : []),
              contextmenuMap[ContextmenuType.copyPreview],
              ...(isDesktop && cellMeta.largeValueId ? [contextmenuMap[ContextmenuType.saveToFile]] : []),
            ]
          : [
              contextmenuMap[ContextmenuType.viewRowDetail],
              contextmenuMap[ContextmenuType.viewUpdateData],
              contextmenuMap[ContextmenuType.copy],
              contextmenuMap[ContextmenuType.paste],
              contextmenuMap[ContextmenuType.setNull],
              contextmenuMap[ContextmenuType.cloneRow],
              contextmenuMap[ContextmenuType.deleteRow],
              contextmenuMap[ContextmenuType.copyRow],
            ]),
      ];
    }

    // If it is not editable, some menus need to be hidden
    if (resultData.canEdit === false) {
      const list = [
        ContextmenuType.setNull,
        ContextmenuType.cloneRow,
        ContextmenuType.deleteRow,
        ContextmenuType.copyRowInsert,
        ContextmenuType.copyRowUpdate,
      ];
      dropdownsList = dropdownsList.filter((item) => !list.includes(item.key));
      contextmenuMap[ContextmenuType.copyRow].children = contextmenuMap[ContextmenuType.copyRow].children.filter(
        (item) => !list.includes(item.key),
      );
    }

    if (!dropdownsList.length) {
      return;
    }

    // VTable opens context menus on mouse-down, while Ant Design closes them on mouse-up.
    // Delay opening until mouse-up so the menu remains visible.
    // If the Vtable is changed later, you can remove this monitoring
    const mouseupFn = () => {
      tableInstance?.contextMenuRef?.current?.openDropdown({
        event: selectEvent.event,
        dropdownsList,
      });
      document.removeEventListener('mouseup', mouseupFn);
    };

    // monitors when the right mouse button is lifted
    document.addEventListener('mouseup', mouseupFn);
  });
  return {
    id,
  };
};

export default onContextmenuCell;
