import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    workspaceLeftActionBar: css`
      display: flex;
      align-items: center;
      justify-content: flex-start;
      height: 34px;
      flex-shrink: 0;
      padding: 0 6px;
      gap: 2px;
      box-sizing: border-box;
      -webkit-app-region: no-drag;
    `,
    rightActions: css`
      display: flex;
      align-items: center;
      gap: 2px;
      margin-left: auto;
      flex-shrink: 0;
    `,
    secondaryAction: css`
      color: ${token.colorTextTertiary};

      &:hover {
        color: ${token.colorTextSecondary};
      }
    `,
    storageMigrationButton: css`
      height: 26px;
      padding-inline: 7px;
      flex-shrink: 0;
      color: ${token.colorTextSecondary};
      font-size: 12px;

      &:hover {
        color: ${token.colorText};
      }
    `,
  };
});
