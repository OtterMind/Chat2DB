import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    searchResult: css`
      height: 100%;
      display: flex;
      flex-direction: column;
      position: relative;
    `,
    tabs: css`
      flex: 1;
      height: 0;
    `,
    historyBar: css`
      flex-shrink: 0;
      height: 28px;
      display: flex;
      align-items: center;
      justify-content: flex-end;
      padding: 0 8px;
      border-bottom: 1px solid ${token.colorBorderSecondary};
      background-color: ${token.colorBgContainer};
    `,
    historyButton: css`
      border: 0;
      background: transparent;
      color: ${token.colorPrimary};
      cursor: pointer;
      font-size: 12px;
      padding: 2px 6px;

      &:hover {
        background-color: ${token.colorFillSecondary};
      }
    `,
    recordIcon: css`
      font-size: 16px;
      margin-right: 4px;
    `,
    resultTabIcon: css`
      margin-right: 4px;
      color: inherit;
    `,
    tableIndex: css`
      width: 50px;
    `,
    monacoEditor: css`
      height: 300px;
      margin: -15px;
    `,
    cursorStateIndicator: css`
      margin: 0 auto;
      max-width: 80%;
      display: flex;
      align-items: center;
      justify-content: center;
    `,
    noData: css`
      height: 100%;
      display: flex;
      flex-direction: column;
      justify-content: center;
      align-items: center;
      font-size: 12px;
      overflow: hidden;
    `,
    outputPrefixIcon: css`
      margin-right: 4px;
    `,
    abstractIcon: css`
      margin-right: 4px;
    `,
  };
});
