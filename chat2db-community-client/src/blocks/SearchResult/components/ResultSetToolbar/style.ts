import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    toolBar: css`
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid ${token.colorBorderLayout};
      padding: 0;
      height: 30px;
      flex-shrink: 0;
      overflow-x: auto;
    `,
    editTableDataBar: css`
      height: 30px;
    `,
    toolBarItem: css`
      flex-shrink: 0;
      height: 100%;
      display: flex;
      justify-content: start;
      padding: 0px 4px;
      gap: 3px;
      align-items: center;
      div i {
        color: ${token.colorText};
      }
      &:not(:last-child) {
        border-right: 1px solid ${token.colorBorderLayout};
      }
    `,
    createChartIcon: css`
      color: ${token.colorText};
    `,
    toolBarRight: css`
      flex: 1;
      flex-shrink: 0;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: end;
      min-width: 66px;
    `,
  };
});
