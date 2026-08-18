import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    nodeFilteringContainer: css`
      border-radius: 8px;
      background-color: ${token.colorBgBase};
      box-shadow: ${token.boxShadow};
      border: 1px solid ${token.colorBorderSecondary};
      width: 278px;
      height: 375px;
      display: flex;
      flex-direction: column;
    `,
    nodeFilterTitle: css`
      font-size: 14px;
      font-weight: 500;
      display: flex;
      align-items: center;
      justify-content: center;
      padding-top: 6px;
    `,
    nodeFilteringHeader: css`
      padding: 4px;
      border-bottom: 1px solid ${token.colorBorderSecondary};
      display: flex;
      align-items: center;
    `,
    nodeFilteringName: css`
      display: flex;
      flex: 1;
      align-items: center;
      width: 0px;
      margin-right: 20px;
      span {
        margin-left: 4px;
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
      }
    `,
    nodeFilteringLogo: css`
      flex-shrink: 0;
    `,
    actionBar: css`
      flex-shrink: 0;
      display: flex;
      /* gap: 8px; */
    `,
    nodeFilteringBody: css`
      padding-bottom: 10px;
      flex: 1;
      overflow: auto;
      display: flex;
      flex-direction: column;
      .ant-tree-switcher-noop {
        display: none;
      }
      .ant-tree .ant-tree-checkbox + span:hover {
        background-color: transparent;
      }
    `,
    treeTitle: css`
      padding: 0px 8px;
      display: flex;
      align-items: center;
    `,
    treeTitleCount: css`
      color: ${token.colorTextSecondary};
      margin-left: 4px;
      font-size: 12px;
    `,
    nodeFilteringBodyHeader: css`
      display: flex;
      align-items: center;
      justify-content: space-between;
      border-bottom: 1px solid ${token.colorBorderSecondary};
      margin: 0px 10px 5px;
      padding: 3px 0px;
    `,
    allSchema: css``,
    clearSelected: css`
      color: ${token.colorTextSecondary};
      font-size: 12px;
      text-align: center;
      padding: 5px 0px;
      cursor: pointer;
      &:hover {
        color: ${token.colorPrimary};
      }
    `,
    allSchemaText: css`
      margin-left: 8px;
    `,
    treeBox: css`
      flex: 1;
      height: 0px;
      overflow: auto;
      margin: 0px 10px;
    `,
    bottomTips: css`
      font-size: 12px;
      line-height: 24px;
      text-align: center;
      color: ${token.colorTextSecondary};
    `,
    searchBar: css`
      width: 100%;
    `,
    treeSelect: css`
      .ant-tree-checkbox {
        margin-inline-end: 0px;
      }
      .ant-tree-node-content-wrapper {
        padding: 0px !important;
      }
      .ant-tree-list-scrollbar-thumb {
        background-color: ${token.colorFill} !important;
        transition: background-color 0.1s ease;
      }
    `,
  };
});
