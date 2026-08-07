import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  viewport: css`
    min-height: 0;
    flex: 1;
    overflow: auto;
    padding: 10px 14px 18px;
    color: ${token.colorText};
    background: ${token.colorBgContainer};
    font-family: Menlo, Monaco, Consolas, 'Courier New', monospace;
    font-size: 12px;
    line-height: 1.65;
  `,
  content: css`
    min-width: 0;
    min-height: 100%;
  `,
  line: css`
    display: grid;
    grid-template-columns: 154px minmax(0, 1fr);
    align-items: start;
    min-height: 20px;

    @media (max-width: 600px) {
      grid-template-columns: 132px minmax(0, 1fr);
    }
  `,
  timestamp: css`
    color: ${token.colorTextSecondary};
    white-space: nowrap;
  `,
  prominentTimestamp: css`
    color: ${token.colorText};
  `,
  message: css`
    display: flex;
    align-items: flex-start;
    gap: 6px;
    min-width: 0;
  `,
  level: css`
    width: 36px;
    flex: none;
    font-size: 11px;
    font-weight: 600;
  `,
  infoLevel: css`
    color: ${token.colorSuccessText};
  `,
  messageText: css`
    min-width: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  `,
  messageINFO: css`
    color: ${token.colorTextSecondary};
  `,
  messageINFOText: css`
    color: ${token.colorText};
  `,
  messageWARN: css`
    color: ${token.colorWarningText};
  `,
  messageERROR: css`
    color: ${token.colorErrorText};
  `,
  empty: css`
    display: flex;
    min-height: 160px;
    height: 100%;
    align-items: center;
    justify-content: center;
    color: ${token.colorTextTertiary};
    text-align: center;
  `,
}));
