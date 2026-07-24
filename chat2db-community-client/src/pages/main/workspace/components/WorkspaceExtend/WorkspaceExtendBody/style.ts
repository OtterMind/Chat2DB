import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    currentWorkspaceExtendBox: css`
      height: 100%;
      border-right: 1px solid ${token.colorBorderLayout};
      overflow: hidden;
    `,
    resultInspectorHost: css`
      width: 100%;
      height: 100%;
      min-width: 0;
      min-height: 0;
      overflow: hidden;
    `,
  };
});
