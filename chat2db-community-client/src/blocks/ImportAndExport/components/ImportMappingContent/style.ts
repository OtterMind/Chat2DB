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
    white-space: nowrap;

    @media (max-width: 720px) {
      white-space: normal;
    }
  `,
  csvOptions: css`
    margin-bottom: 12px;
  `,
  csvOptionField: css`
    display: flex;
    min-width: 0;
    flex-direction: column;
    gap: 4px;

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
    padding-top: 4px;

    .ant-collapse-header {
      min-height: 40px;
      align-items: center !important;
      padding: 6px 0 !important;
      font-weight: 600;
    }

    .ant-collapse-content-box {
      padding: 4px 0 10px !important;
    }
  `,
  csvFormatOptions: css`
    display: grid;
    grid-template-columns: 130px 150px 160px 170px;
    gap: 8px;

    @media (max-width: 900px) {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  `,
  sourceRowOptions: css`
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 160px));
    gap: 8px;

    .ant-input-number {
      width: 100%;
    }
  `,
  sourceRowHasHeader: css`
    grid-column: 1 / -1;
    width: fit-content;
    font-size: 12px;
  `,
  formatOptions: css`
    display: grid;
    grid-template-columns: 1fr 1.15fr 1fr 1.3fr 1fr 1fr;
    gap: 8px;

    @media (max-width: 900px) {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  `,
  dateExamples: css`
    display: grid;
    grid-column: 1 / -1;
    grid-template-columns: 1fr repeat(4, auto);
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
      grid-template-columns: 1fr 1fr;

      > span:first-child {
        grid-column: 1 / -1;
      }

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
  mappingControls: css`
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: flex-end;
    margin-bottom: 8px;

    .ant-checkbox-wrapper {
      white-space: nowrap;
    }
  `,
  unmappedTargetSelect: css`
    width: 220px;
  `,
  unmappedSource: css`
    color: ${token.colorTextSecondary};
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
    flex: none;
    padding-top: 6px;
    background: ${token.colorBgElevated};
    text-align: right;
  `,
}));
