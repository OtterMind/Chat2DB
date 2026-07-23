import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }, { wrap }: { wrap: boolean }) => {
  return {
    sqlPreview: css`
      width: 100%;
      max-width: 100%;
      min-width: 0;
      position: relative;
      overflow-x: hidden;
      overflow-y: auto;
      box-sizing: border-box;
    `,
    highlighter: css`
      width: 100%;
      max-width: 100%;
      min-width: 0;
      overflow: hidden;
      box-sizing: border-box;

      pre {
        margin: 0;
        width: 100%;
        max-width: 100%;
        min-width: 0;
        overflow-x: auto;
        overflow-y: hidden;
        box-sizing: border-box;
      }

      && pre code {
        display: block;
        width: ${wrap ? '100%' : 'max-content'};
        max-width: ${wrap ? '100%' : 'none'};
        min-width: ${wrap ? '0' : '100%'};
        overflow: visible;
        white-space: ${wrap ? 'pre-wrap' : 'pre'};
        overflow-wrap: ${wrap ? 'anywhere' : 'normal'};
        word-break: ${wrap ? 'break-word' : 'normal'};
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
