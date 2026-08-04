import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  editor: css`
    position: relative;
    display: flex;
    flex: 1;
    flex-direction: column;
    width: 100%;
    height: 100%;
    min-height: 0;
  `,
  editorBody: css`
    position: relative;
    flex: 1;
    min-height: 0;
  `,
  cursorStatus: css`
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    height: 24px;
    padding: 0 10px;
    color: ${token.colorTextSecondary};
    background: ${token.colorBgContainer};
    font-family: ${token.fontFamilyCode};
    font-size: 12px;
    line-height: 24px;
    letter-spacing: 0;
    user-select: none;
  `,
}));
