import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => {
  return {
    workspaceRoot: css`
      width: 100%;
      height: 100%;
    `,
    leftContainer: css`
      display: relative;
      height: 100%;
    `,
  };
});
