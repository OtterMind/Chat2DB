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
    flex-wrap: wrap;
    gap: 8px;
    padding: 12px 14px;
    font-size: 13px;
  `,
  status: css`
    margin-left: auto;
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  question: css`
    padding: 0 14px 12px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  `,
  options: css`
    display: grid;
    gap: 8px;
    padding: 0 14px 12px;
  `,
  option: css`
    width: 100%;
    height: auto;
    padding: 10px 12px;
    text-align: left;
    justify-content: flex-start;
    white-space: normal;
  `,
  optionContent: css`
    display: grid;
    gap: 4px;
    min-width: 0;
    overflow-wrap: anywhere;
  `,
  description: css`
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  form: css`
    display: grid;
    gap: 10px;
    padding: 0 14px 12px;
  `,
  actions: css`
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  `,
  answer: css`
    display: grid;
    gap: 6px;
    padding: 0 14px 12px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  `,
  error: css`
    padding: 0 14px 12px;
    color: ${token.colorError};
  `,
}));
