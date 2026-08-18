import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    redisAllData: css`
      height: 100%;
      display: flex;
      flex-direction: column;
    `,
    upperBox: css`
      display: flex;
      flex-direction: column;
      width: 100%;
      position: relative;
      z-index: 0;
      height: 100%;
    `,
    emptyStatus: css`
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100%;
    `,
    operationAllDataBar: css`
      height: 34px;
      flex-shrink: 0;
      display: flex;
      align-items: center;
      padding: 0px 4px 0px 4px;
      border-bottom: 1px solid ${token.colorBorder};
    `,
    left: css`
      flex: 1;
      display: flex;
      align-items: center;
      gap: 4px;
      color: ${token.colorTextSecondary};
    `,
    right: css`
      display: flex;
      align-items: center;
      gap: 6px;
      width: 420px;
      max-width: 48%;
      height: 24px;
      flex-shrink: 0;
    `,
    viewMode: css`
      flex-shrink: 0;
    `,
    viewModeIcon: css`
      width: 18px;
      height: 18px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
    `,
    searchBar: css`
      flex: 1;
      height: 24px;
    `,
    tableCell: css`
      flex: 1;
      padding: 0px 8px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    tableBox: css`
      width: 100%;
      height: 100%;
    `,
    editDataSide: css`
      height: 100%;
      padding: 10px 0px;
      width: 100%;
      position: relative;
      overflow-y: auto;
      overflow-x: hidden;
      box-sizing: border-box;
    `,
    closeEditPane: css`
      position: absolute;
      top: 6px;
      right: 8px;
      z-index: 2;
      width: 24px;
      min-width: 24px;
      height: 24px;
      padding: 0;
      color: ${token.colorTextSecondary};
    `,
  };
});
