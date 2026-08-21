import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    saveModule: css`
      height: 100%;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    `,
    saveBoxList: css`
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      overflow-x: hidden;
      padding: 0 8px 8px;
    `,
    loadingContent: css`
      height: auto;
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
      &:hover .save-item-delete {
        opacity: 1;
      }
    `,
    consoleRow: css`
      .save-tree-title {
        color: ${token.colorTextSecondary};
      }
    `,
    dataSourceRow: css`
      .save-tree-title {
        color: ${token.colorText};
        font-weight: 500;
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
    searchHighlight: css`
      color: ${token.colorError};
    `,
    saveItemDelete: css`
      opacity: 0;
      transition: opacity 0.15s;
      flex-shrink: 0;
      width: 18px;
      height: 18px;
      display: flex;
      align-items: center;
      justify-content: center;
      margin-right: 8px;
      color: ${token.colorTextTertiary};
    `,
  };
});
