import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    fileTreeModule: css`
      height: 100%;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      outline: none;
    `,
    header: css`
      display: flex;
      align-items: center;
      justify-content: flex-start;
      flex-shrink: 0;
      height: 34px;
      padding: 0 8px 0 4px;
      font-size: 12px;
      color: ${token.colorTextTertiary};
      white-space: nowrap;
      position: relative;
      overflow: hidden;
    `,
    headerContent: css`
      display: flex;
      align-items: center;
      justify-content: flex-start;
    `,
    headerActions: css`
      display: flex;
      align-items: center;
      gap: 2px;
      flex-shrink: 0;
    `,
    treeBox: css`
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      overflow-x: hidden;
      padding: 0 8px 8px 4px;
    `,
    treeList: css`
      padding: 2px 0;
    `,
    treeRow: css`
      position: relative;
      display: flex;
      align-items: center;
      height: 28px;
      border-radius: 4px;
      padding-right: 4px;
      cursor: pointer;
      user-select: none;
      transition: background-color 0.15s;
      &:hover {
        background-color: ${token.colorFillTertiary};
      }
    `,
    selectedRow: css`
      background-color: ${token.colorPrimaryBg};
      box-shadow: inset 2px 0 0 ${token.colorPrimary};
      &:hover {
        background-color: ${token.colorPrimaryBgHover};
      }
      .local-sql-tree-title {
        color: ${token.colorPrimaryText};
      }
      .local-sql-tree-icon {
        color: ${token.colorPrimary};
      }
    `,
    dropTargetRow: css`
      background-color: ${token.colorPrimaryBgHover};
      box-shadow: inset 2px 0 0 ${token.colorPrimary};

      .local-sql-tree-title {
        color: ${token.colorPrimaryText};
      }
      .local-sql-tree-icon {
        color: ${token.colorPrimary};
      }
    `,
    createRow: css`
      cursor: default;
      &:hover {
        background-color: transparent;
      }
    `,
    createInput: css`
      flex: 1;
      min-width: 0;
      height: 22px;
      font-size: 13px;
      .ant-input {
        height: 20px;
        font-size: 13px;
      }
    `,
    disabledRow: css`
      cursor: default;
      .local-sql-tree-title {
        color: ${token.colorTextQuaternary};
      }
      .local-sql-tree-icon {
        color: ${token.colorTextQuaternary};
      }
    `,
    switcherButton: css`
      width: 20px;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      padding: 0;
      border: none;
      background: transparent;
      color: ${token.colorTextQuaternary};
      cursor: pointer;
    `,
    switcherIcon: css`
      transition: transform 0.15s ease;
    `,
    switcherIconExpanded: css`
      transform: rotate(90deg);
    `,
    treeNodeIcon: css`
      width: 22px;
      height: 24px;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      transform: translateX(-2px);
      color: ${token.colorTextSecondary};
    `,
    treeNodeTitle: css`
      font-size: 13px;
      color: ${token.colorText};
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      flex: 1;
      min-width: 0;
      line-height: 22px;
    `,
    emptyBox: css`
      height: 100%;
      min-height: 160px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 10px;
      padding: 20px;
      color: ${token.colorTextTertiary};
      text-align: center;
      font-size: 12px;
    `,
    emptyButton: css`
      height: 28px;
      padding: 0 10px;
      border-radius: 4px;
    `,
    loadingText: css`
      flex-shrink: 0;
      margin-left: 4px;
      color: ${token.colorTextQuaternary};
      font-size: 12px;
    `,
    contextMenu: css`
      min-width: 250px;
      padding: 6px;
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 8px;
      background: ${token.colorBgElevated};
      box-shadow: ${token.boxShadowSecondary};

      .ant-menu-item {
        height: 30px;
        margin: 0;
        padding: 0 10px;
        border-radius: 5px;
        color: ${token.colorText};
        font-size: 13px;
        line-height: 30px;
      }

      .ant-menu-item:hover {
        background: ${token.colorFillTertiary};
      }

      .ant-menu-item-danger {
        color: ${token.colorError};
      }

      .ant-menu-item-disabled {
        color: ${token.colorTextQuaternary} !important;
      }

      .ant-menu-item-divider {
        margin: 6px 8px;
        background: ${token.colorSplit};
      }
    `,
  };
});
