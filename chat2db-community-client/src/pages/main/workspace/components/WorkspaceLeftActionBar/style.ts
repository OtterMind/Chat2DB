import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    searchRow: css`
      display: flex;
      align-items: center;
      height: 42px;
      padding: 0 10px;
      border-bottom: 1px solid ${token.colorBorderLayout};
      box-sizing: border-box;
    `,
    workspaceLeftActionBar: css`
      display: flex;
      align-items: center;
      justify-content: flex-start;
      height: 34px;
      padding: 0 6px 0 4px;
      gap: 4px;
      box-sizing: border-box;
    `,
    rightActions: css`
      display: flex;
      align-items: center;
      gap: 4px;
      margin-left: auto;
      flex-shrink: 0;
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
    searchBar: css`
      width: 100%;
      max-width: 100%;
      background-color: ${token.colorFillTertiary};
      height: 25px;
    `,
    searchMatchCount: css`
      color: ${token.colorTextQuaternary};
      font-size: 12px;
      line-height: 1;
      white-space: nowrap;
      user-select: none;
    `,
  };
});
