import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  console: css`
    height: 100%;
    display: flex;
    flex-direction: column;
    background: ${token.colorBgContainer};
    color: ${token.colorText};
  `,
  scrollArea: css`
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 10px 14px 18px;
    font-family: Menlo, Monaco, Consolas, 'Courier New', monospace;
    font-size: 12px;
    line-height: 1.65;
  `,
  scrollContent: css`
    min-width: 0;
  `,
  record: css`
    min-width: 0;
    margin-bottom: 10px;
  `,
  line: css`
    display: grid;
    grid-template-columns: 154px minmax(0, 1fr);
    align-items: start;
    min-height: 20px;
  `,
  timestamp: css`
    color: ${token.colorTextSecondary};
    white-space: nowrap;
  `,
  prominentTimestamp: css`
    color: ${token.colorText};
  `,
  contextLine: css`
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 10px;
    min-width: 0;
    margin: 16px 0 12px;
    color: ${token.colorText};
    font-weight: 500;
  `,
  contextRule: css`
    flex: 1;
    min-width: 24px;
    height: 1px;
    background: ${token.colorBorderSecondary};
  `,
  contextContent: css`
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
  `,
  contextText: css`
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  databaseIcon: css`
    flex: none;
    width: 14px;
    height: 14px;
  `,
  sqlContent: css`
    display: flex;
    align-items: flex-start;
    min-width: 0;
  `,
  prompt: css`
    flex: none;
    margin-right: 8px;
    color: ${token.colorPrimary};
    font-weight: 600;
  `,
  sql: css`
    flex: 1;
    min-width: 0;
    overflow: visible;
    font: inherit;
    letter-spacing: 0;

    > div {
      box-sizing: border-box;
      background-color: ${token.colorFillQuaternary} !important;
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 4px !important;
    }

    > div > div:first-child {
      top: 2px;
      right: 2px;
      z-index: 1;
    }

    pre {
      margin: 0;
      padding: 2px 30px 2px 4px !important;
      font: inherit;
      line-height: inherit;
      background: transparent !important;
    }

    code {
      width: auto !important;
      overflow: visible !important;
      font: inherit;
      line-height: inherit;
      white-space: pre-wrap !important;
      overflow-wrap: anywhere;
      letter-spacing: 0;
    }
  `,
  message: css`
    display: flex;
    align-items: flex-start;
    gap: 8px;
    min-width: 0;
  `,
  level: css`
    flex: none;
    width: 42px;
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
  resultLine: css`
    color: ${token.colorSuccessText};
    min-width: 0;
  `,
  resultError: css`
    color: ${token.colorErrorText};
  `,
  resultLink: css`
    border: 0;
    padding: 0;
    background: transparent;
    color: ${token.colorLink};
    font: inherit;
    cursor: pointer;

    &:hover {
      text-decoration: underline;
    }
  `,
  metrics: css`
    color: ${token.colorTextSecondary};
  `,
  released: css`
    color: ${token.colorTextQuaternary};
  `,
  inlineAction: css`
    height: 20px;
    margin-left: 8px;
    padding: 0 4px;
    font-size: 12px;
  `,
  runningLine: css`
    color: ${token.colorTextSecondary};
  `,
  runningContent: css`
    display: inline-flex;
    align-items: center;
    gap: 7px;
  `,
  runningDot: css`
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: ${token.colorPrimary};
    animation: console-pulse 1.2s ease-in-out infinite;

    @keyframes console-pulse {
      0%, 100% { opacity: 0.35; }
      50% { opacity: 1; }
    }
  `,
  successLine: css`
    color: ${token.colorSuccessText};
  `,
  cancelledLine: css`
    color: ${token.colorWarningText};
  `,
}));
