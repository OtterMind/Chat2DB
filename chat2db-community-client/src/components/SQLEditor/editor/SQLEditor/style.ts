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
  editorStatusOverlay: css`
    position: absolute;
    right: 10px;
    bottom: 4px;
    z-index: 2;
    display: flex;
    max-width: calc(100% - 20px);
    align-items: center;
    gap: 8px;
    color: ${token.colorTextSecondary};
    font-family: ${token.fontFamilyCode};
    font-size: 12px;
    letter-spacing: 0;
    overflow: hidden;
    pointer-events: none;
  `,
  fileEncodingControl: css`
    min-width: 0;
    pointer-events: auto;
  `,
  cursorPosition: css`
    flex: 0 0 auto;
    white-space: nowrap;
    user-select: none;
  `,
}));
