import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  container: css`
    display: flex;
    max-height: min(82vh, calc(100vh - 116px));
    max-height: min(82dvh, calc(100dvh - 116px));
    min-width: 0;
    flex-direction: column;
  `,
  scrollContent: css`
    min-height: 0;
    overflow-y: auto;
    padding-right: 4px;

    &::-webkit-scrollbar {
      width: 6px;
    }

    &::-webkit-scrollbar-thumb {
      border-radius: 999px;
      background-color: transparent;
    }

    &:hover::-webkit-scrollbar-thumb {
      background-color: ${token.colorFill};
    }
  `,
  error: css`
    margin-bottom: 8px;
    color: ${token.colorError};
  `,
  previewState: css`
    display: flex;
    min-height: min(430px, 50vh);
    min-height: min(430px, 50dvh);
    align-items: center;
    justify-content: center;
  `,
  previewError: css`
    display: flex;
    max-width: calc(100% - 32px);
    flex-direction: column;
    align-items: center;
    gap: 10px;
    color: ${token.colorError};
    line-height: 1.6;
    text-align: center;
    white-space: normal;
    overflow-wrap: anywhere;
  `,
  csvOptions: css`
    margin-bottom: 12px;
  `,
  csvOptionField: css`
    display: grid;
    min-width: 0;
    grid-template-columns: var(--import-field-columns, minmax(0, 1fr));
    gap: var(--import-field-gap, 4px);
    align-items: center;

    > span:first-child {
      display: flex;
      align-items: center;
      gap: 6px;
      color: ${token.colorTextSecondary};
      font-size: 12px;
      white-space: nowrap;
    }
  `,
  fullWidthControl: css`
    width: 100%;
  `,
  customCharacterInput: css`
    padding: 4px 8px;
  `,
  sections: css`
    display: flex;
    padding-top: 4px;
    flex-direction: column;
    gap: 14px;
  `,
  fillSections: css`
    --import-format-columns: minmax(0, 1fr);
    --import-source-columns: minmax(0, 1fr);
    --import-value-columns: minmax(0, 1fr);
    --import-field-columns: 132px minmax(0, 420px);
    --import-field-gap: 16px;
    --import-options-gap: 16px;
    --import-checkbox-offset: 148px;
    flex: 1;
    gap: 22px;

    > section {
      display: grid;
      grid-template-columns: 100px minmax(0, 1fr);
      align-items: start;
      gap: 20px;
    }

    > section + section {
      padding-top: 20px;
      border-top: 1px solid ${token.colorBorderSecondary};
    }

    @media (max-width: 700px) {
      --import-field-columns: minmax(0, 1fr);
      --import-field-gap: 6px;
      --import-checkbox-offset: 0;
      > section { grid-template-columns: 1fr; gap: 10px; }
    }
  `,
  sectionBlock: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 6px;
  `,
  sectionHeading: css`
    font-size: 14px;
    font-weight: 600;
  `,
  sectionBody: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 16px;
  `,
  csvFormatOptions: css`
    display: grid;
    grid-template-columns: var(--import-format-columns, 130px 150px 160px 170px);
    gap: var(--import-options-gap, 8px);

    @media (max-width: 900px) {
      grid-template-columns: var(--import-format-columns, repeat(2, minmax(0, 1fr)));
    }
  `,
  sourceRowOptions: css`
    display: grid;
    grid-template-columns: var(--import-source-columns, repeat(3, minmax(0, 160px)));
    gap: var(--import-options-gap, 8px);

    .ant-input-number {
      width: 100%;
    }
  `,
  sourceRowHasHeader: css`
    grid-column: 1 / -1;
    width: fit-content;
    margin-inline-start: var(--import-checkbox-offset, 0);
    font-size: 12px;
  `,
  formatOptions: css`
    display: grid;
    grid-template-columns: var(--import-value-columns, 1fr 1.15fr 1fr 1.3fr 1fr 1fr);
    gap: var(--import-options-gap, 8px);

    @media (max-width: 900px) {
      grid-template-columns: var(--import-value-columns, repeat(2, minmax(0, 1fr)));
    }
  `,
  dateExamples: css`
    display: grid;
    grid-column: 1 / -1;
    grid-template-columns: var(--import-field-columns, 1fr auto);
    gap: 18px;
    align-items: center;
    color: ${token.colorTextSecondary};
    font-size: 12px;

    code {
      color: ${token.colorText};
      font-family: inherit;
      white-space: nowrap;
    }

    code:not(:first-of-type) {
      padding-left: 18px;
      border-left: 1px solid ${token.colorBorderSecondary};
    }

    @media (max-width: 900px) {
      code:not(:first-of-type) {
        padding-left: 0;
        border-left: 0;
      }
    }
  `,
  sectionTitle: css`
    grid-column: 1 / -1;
    font-size: 14px;
  `,
  dataPanels: css`
    display: flex;
    height: 100%;
    min-height: 0;
    flex-direction: column;
    overflow: hidden;
  `,
  dataPane: css`
    display: flex;
    min-width: 0;
    min-height: 0;
    flex-direction: column;
    overflow: hidden;
  `,
  paneTitle: css`
    flex: none;
    padding-bottom: 12px;
    font-weight: 500;
  `,
  columnType: css`
    color: ${token.colorTextSecondary};
    font-size: 12px;
    overflow-wrap: anywhere;
  `,
  dataTable: css`
    flex: 1;
    min-height: 0;
    overflow: hidden;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: ${token.borderRadius}px;

    .ant-spin-nested-loading, .ant-spin-container, .ant-table, .ant-table-container {
      height: 100%;
      min-height: 0;
    }

    .ant-table-container { display: flex; flex-direction: column; }
    .ant-table-container::before, .ant-table-container::after { box-shadow: none !important; }
    .ant-table-header { flex: none; }
    .ant-table-body { flex: 1; min-height: 0; max-height: none !important; overflow: auto !important; }
    .ant-table-cell { white-space: nowrap; }
    .ant-table-body::-webkit-scrollbar { width: 10px; height: 10px; }
    .ant-table-body::-webkit-scrollbar-thumb { border-radius: 6px; background: ${token.colorTextQuaternary}; }
    .ant-table-body::-webkit-scrollbar-corner { background: ${token.colorBgContainer}; }
  `,
  mappingControls: css`
    display: flex;
    flex: none;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    margin-top: 16px;
    padding-top: 14px;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,
  mappingTargetPolicy: css`
    display: flex;
    align-items: center;
    gap: 12px;
  `,
  unmappedTargetSelect: css`
    width: 220px;
  `,
  unmappedSource: css`
    color: ${token.colorTextSecondary};
  `,
  mappingControlLabel: css`
    color: ${token.colorTextSecondary};
    font-size: 12px;
    white-space: nowrap;
  `,
  mappingOrigin: css`
    display: inline-flex;
    gap: 6px;
    align-items: center;
  `,
  mappingOriginTag: css`
    margin-inline-end: 0;
    color: ${token.colorTextSecondary};
    font-size: 11px;
    line-height: 16px;
  `,
  unmappedStatus: css`
    color: ${token.colorWarning};
  `,
  mappingRowUnmapped: css`
    > td:first-child {
      box-shadow: inset 3px 0 0 ${token.colorWarningBorder};
    }
  `,
  requiredStatus: css`
    color: ${token.colorError};
  `,
  targetColumnCell: css`
    position: relative;
  `,
  mappingWarningSlot: css`
    position: absolute;
    z-index: 1;
    top: 50%;
    left: 10px;
    display: flex;
    width: 16px;
    height: 16px;
    transform: translateY(-50%);
  `,
  mappingWarningIcon: css`
    display: flex;
    color: ${token.colorWarning};
    cursor: help;
  `,
  targetColumnSelect: css`
    width: 100%;
  `,
  targetColumnSelectWarning: css`
    .ant-select-selector {
      padding-left: 34px !important;
    }
  `,
  mappingTable: css`
    .ant-table-tbody > tr > td {
      border-bottom: 0;
    }
  `,
  scrollableTable: css`
    .ant-table-ping-left:not(.ant-table-has-fix-left) .ant-table-container::before,
    .ant-table-ping-right:not(.ant-table-has-fix-right) .ant-table-container::after {
      box-shadow: none;
    }

    .ant-table-body {
      overflow-y: auto !important;
      -ms-overflow-style: none;
      scrollbar-width: none;

      &::-webkit-scrollbar {
        display: none;
        width: 0;
        height: 0;
      }
    }
  `,
  actions: css`
    z-index: 2;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 16px;
    flex: none;
    padding-top: 6px;
    background: ${token.colorBgElevated};
    text-align: right;
  `,
}));
