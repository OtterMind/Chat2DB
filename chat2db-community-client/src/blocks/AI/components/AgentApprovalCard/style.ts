import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  card: css`
    min-width: 0;
    max-width: 100%;
    margin: 12px 0;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 10px;
    background: ${token.colorBgContainer};
    overflow: hidden;
  `,
  header: css`
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 14px;
    font-size: 13px;
  `,
  status: css`
    margin-left: auto;
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  directory: css`
    display: grid;
    gap: 4px;
    padding: 0 14px 10px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    code { overflow-wrap: anywhere; }
  `,
  command: css`
    margin: 0;
    padding: 12px 14px;
    max-height: 240px;
    overflow: auto;
    color: ${token.colorText};
    background: ${token.colorFillQuaternary};
    border-block: 1px solid ${token.colorBorderSecondary};
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  `,
  footer: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 10px;
    padding: 10px 14px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  actions: css`
    display: flex;
    gap: 8px;
    margin-left: auto;
  `,
  error: css`
    padding: 0 14px 12px;
    color: ${token.colorError};
    font-size: 12px;
    overflow-wrap: anywhere;
  `,
}));
