import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  trigger: css`
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    padding: 0;
    border: 0;
    border-radius: 4px;
    color: ${token.colorTextSecondary};
    background: transparent;
    cursor: pointer;
    &:hover { color: ${token.colorPrimary}; background: ${token.colorFillSecondary}; }
  `,
  panel: css`
    width: 370px;
    max-width: calc(100vw - 48px);
    max-height: calc(100vh - 64px);
    overflow-y: auto;
  `,
  title: css`
    margin-bottom: 12px;
    color: ${token.colorText};
    font-weight: 600;
  `,
  directory: css`
    display: grid;
    gap: 8px;
    margin-bottom: 12px;
  `,
  hint: css`
    color: ${token.colorTextSecondary};
    font-size: 12px;
  `,
  directoryLabel: css`
    display: flex;
    align-items: center;
    gap: 6px;
  `,
  help: css`
    display: inline-flex;
    padding: 0;
    border: 0;
    background: transparent;
    color: ${token.colorTextSecondary};
    cursor: help;
  `,
  tools: css`
    max-height: min(48vh, 390px);
    overflow-y: auto;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,
  row: css`
    padding: 8px 0;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    &:last-child { border-bottom: 0; }
  `,
  rowHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    code { overflow-wrap: anywhere; font-size: 12px; }
  `,
}));
