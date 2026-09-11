import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    toolBar: css`
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid ${token.colorBorderLayout};
      padding: 0;
      height: 34px;
      flex-shrink: 0;
      box-sizing: border-box;
      overflow-x: auto;
    `,
    editTableDataBar: css`
      height: 34px;
    `,
    toolBarItem: css`
      flex-shrink: 0;
      height: 100%;
      display: flex;
      justify-content: start;
      padding: 0px 4px;
      gap: 3px;
      align-items: center;
      &:not(:last-child) {
        border-right: 1px solid ${token.colorBorderLayout};
      }
    `,
    toolbarAction: css`
      &:not(:disabled),
      &:not(:disabled) i,
      &:not(:disabled) svg {
        color: ${token.colorTextSecondary};
      }

      &:hover:not(:disabled),
      &:hover:not(:disabled) i,
      &:hover:not(:disabled) svg {
        color: ${token.colorText};
      }
    `,
    pendingAction: css`
      &&,
      && i,
      && svg {
        color: ${token.colorPrimary};
      }
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
