import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    canvasTable: css`
      height: 100%;
    `,
    headerTooltip: css`
      border-radius: 4px;
      background-color: ${token.colorBgBase};
      box-shadow: ${token.boxShadow};
      border: 1px solid ${token.colorBorderSecondary};
      padding: 8px 10px;
      min-width: 220px;
      max-width: 420px;
    `,
    headerTooltipRow: css`
      display: grid;
      grid-template-columns: max-content minmax(120px, 1fr);
      align-items: center;
      min-height: 24px;
      column-gap: 12px;
    `,
    headerTooltipLabel: css`
      font-size: 14px;
      color: ${token.colorTextSecondary};
    `,
    headerTooltipValue: css`
      min-width: 0;
      font-size: 14px;
      color: ${token.colorText};
      overflow-wrap: anywhere;
    `,
  };
});
