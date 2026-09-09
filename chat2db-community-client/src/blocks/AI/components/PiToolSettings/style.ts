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
  actions: css`
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  `,
  tools: css`
    max-height: min(48vh, 390px);
    overflow-y: auto;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,
  group: css`
    margin: 12px 0 6px;
    color: ${token.colorTextSecondary};
    font-size: 12px;
    font-weight: 600;
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
