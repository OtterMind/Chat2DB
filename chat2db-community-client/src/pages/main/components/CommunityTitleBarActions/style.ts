import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  toolbar: css`
    display: flex;
    align-items: center;
    justify-content: flex-end;
    width: 100%;
    min-width: 0;
    height: 100%;
    -webkit-app-region: drag;
  `,
  workspaceActions: css`
    display: flex;
    align-items: center;
    min-width: 0;
    overflow-x: auto;
    overflow-y: hidden;
    scrollbar-width: none;
    -webkit-app-region: no-drag;

    &::-webkit-scrollbar {
      display: none;
    }
  `,
  layoutActions: css`
    flex-shrink: 0;
    margin-left: 4px;
    padding-left: 6px;
    border-left: 1px solid ${token.colorBorderSecondary};
  `,
}));
