import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    canvasTable: css`
      height: 100%;
    `,
    columnSearch: css`
      margin-bottom: 12px;
    `,
    columnVisibilityTitle: css`
      display: inline-flex;
      align-items: center;
      gap: 6px;
    `,
    columnVisibilityHelp: css`
      width: 20px;
      height: 20px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      margin: 0;
      padding: 0;
      border: 0;
      background: transparent;
      color: ${token.colorTextTertiary};
      cursor: help;

      &:hover {
        color: ${token.colorTextSecondary};
      }

      &:focus-visible {
        outline: 2px solid ${token.colorPrimaryBorder};
        outline-offset: -2px;
      }
    `,
    columnVisibilityList: css`
      display: flex;
      max-height: min(420px, 55vh);
      flex-direction: column;
      gap: 2px;
      overflow-y: auto;
    `,
    columnVisibilityItem: css`
      width: 100%;
      min-height: 32px;
      margin: 0 !important;
      padding: 5px 8px;
      border-radius: 4px;

      &:hover {
        background: ${token.colorFillTertiary};
      }

      .ant-checkbox + span {
        display: grid;
        min-width: 0;
        flex: 1;
        align-items: center;
        grid-template-columns: minmax(0, 1fr) minmax(0, 0.8fr) minmax(0, 1.4fr);
        gap: 16px;
      }
    `,
    columnVisibilityName: css`
      min-width: 0;
      overflow: hidden;
      color: ${token.colorText};
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    columnVisibilityType: css`
      min-width: 0;
      overflow: hidden;
      color: ${token.colorPrimary};
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    columnVisibilityComment: css`
      min-width: 0;
      overflow: hidden;
      color: ${token.colorTextSecondary};
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
  };
});
