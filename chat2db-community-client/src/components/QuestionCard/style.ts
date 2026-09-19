import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  card: css`
    min-width: 0;
    max-width: 100%;
    margin: 12px 0;
    border: 1px solid ${token.colorPrimaryBorder};
    border-radius: 10px;
    background: ${token.colorBgElevated};
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
    overflow: hidden;
  `,
  header: css`
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    padding: 12px 14px;
    font-size: 13px;
    color: ${token.colorText};
    background: ${token.colorPrimaryBg};
  `,
  status: css`
    margin-left: auto;
    color: ${token.colorPrimary};
    font-size: 12px;
    font-weight: 600;
  `,
  question: css`
    padding: 14px;
    color: ${token.colorText};
    font-size: 14px;
    line-height: 1.75;
    font-weight: 500;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  `,
  options: css`
    display: grid;
    gap: 8px;
    margin: 0 14px 14px;
    padding: 12px;
    border-radius: 8px;
    border: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorFillTertiary};
    color: ${token.colorText};
    font-size: 14px;
    line-height: 1.7;
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
    margin: 0 14px 14px;
    padding: 12px;
    border-radius: 6px;
    background: ${token.colorPrimaryBg};
    color: ${token.colorText};
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  `,
  label: css`
    margin-bottom: 4px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    font-weight: 600;
  `,
  error: css`
    padding: 0 14px 12px;
    color: ${token.colorError};
  `,
}));
