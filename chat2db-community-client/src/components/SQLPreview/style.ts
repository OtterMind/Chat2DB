import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => {
  return {
    sqlPreview: css`
      width: 100%;
      min-width: 0;
      position: relative;
      overflow: auto;
      box-sizing: border-box;
    `,
    highlighter: css`
      width: 100%;

      pre {
        margin: 0;
      }
    `,
    wrappedHighlighter: css`
      && pre code {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }
    `,
    transparentHighlighter: css`
      background-color: transparent !important;
      border-radius: 0 !important;

      pre {
        border-radius: 0 !important;
      }
    `,
  };
});
