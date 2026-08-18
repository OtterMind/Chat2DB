import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    workspaceRight: css`
      position: relative;
      display: flex;
      height: 100%;
      background-color: ${token.colorBgBase};
    `,
    draggablePanel: css`
      position: relative;
      flex: 1;
    `,
    masterScope: css`
      height: 100%;
      display: flex;
      flex-direction: column;
    `,
    masterScopeMain: css`
      flex: 1;
      height: 0px;
    `,
  };
});
