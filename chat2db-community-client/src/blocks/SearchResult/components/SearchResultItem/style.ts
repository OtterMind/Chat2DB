import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    resultContent: css`
      height: 100%;
      min-height: 0;
    `,
    successResult: css`
      height: 100%;
      .successResultContent {
        height: 100%;
      }
    `,
    errorResult: css`
      height: 100%;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 12px;
      padding: 16px 24px;
      overflow: auto;
    `,
    errorIcon: css`
      color: ${token.colorError};
      font-size: 32px;
      line-height: 1;
    `,
    errorMessage: css`
      max-width: 80%;
      max-height: 200px;
      overflow: auto;
      color: ${token.colorErrorText};
      font-size: 13px;
      line-height: 1.6;
      text-align: center;
      white-space: pre-wrap;
      word-break: break-word;
    `,
    updateCountBox: css`
      height: 100%;
    `,
    updateCount: css`
      height: calc(100% - 26px);
      display: flex;
      justify-content: center;
      align-items: center;
    `,
  };
});
