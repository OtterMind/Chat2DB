import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    treeBox: css`
      display: flex;
      flex-direction: column;
      font-size: 14px;
      min-width: 0;
      min-height: 0;
      overflow: hidden;

      .ant-tree {
        flex: 1 1 auto;
        width: 100%;
        height: 100%;
        min-height: 0;
        background-color: transparent;
      }

      .ant-tree-list,
      .ant-tree-list-holder {
        height: 100%;
        min-height: 0;
      }

      .ant-tree-list-holder {
        max-height: 100% !important;
      }

      .ant-tree-list-scrollbar-horizontal {
        bottom: 0 !important;
        height: 6px !important;
        visibility: visible !important;
        opacity: 0;
        pointer-events: auto;
        transition: opacity 0.1s ease;
      }

      .ant-tree-list-scrollbar-horizontal .ant-tree-list-scrollbar-thumb {
        background-color: ${token.colorFill} !important;
      }

      .ant-tree-list-scrollbar-horizontal:active,
      .ant-tree-list-scrollbar-horizontal:has(.ant-tree-list-scrollbar-thumb:active) {
        opacity: 1;
      }

      .ant-tree-switcher {
        display: none;
      }

      .ant-tree-treenode.chat2db-data-source-identity-node {
        position: relative;
        background-color: var(--chat2db-data-source-identity-tint);
      }

      .ant-tree-treenode.chat2db-data-source-identity-node::before {
        position: absolute;
        z-index: 1;
        top: 2px;
        bottom: 2px;
        left: 0;
        width: 1px;
        background-color: var(--chat2db-data-source-identity-color);
        box-shadow: 1px 0 0 ${token.colorBorder};
        content: '';
        pointer-events: none;
      }

      .ant-tree-treenode.chat2db-data-source-identity-root::before {
        width: 3px;
        border-radius: 1px;
        box-shadow: 0 0 0 1px ${token.colorBorder};
      }

      .ant-tree-treenode.chat2db-data-source-identity-node .ant-tree-node-content-wrapper {
        background-color: transparent;
      }

      .ant-tree-treenode.chat2db-data-source-identity-node:hover,
      .ant-tree-treenode.chat2db-data-source-identity-node:focus-within {
        background-color: ${token.colorFillSecondary};
      }

      .ant-tree-treenode.chat2db-data-source-identity-node:hover .ant-tree-node-content-wrapper,
      .ant-tree-treenode.chat2db-data-source-identity-node:focus-within .ant-tree-node-content-wrapper {
        background-color: transparent !important;
      }

      .ant-tree-node-content-wrapper {
        white-space: nowrap;
      }

      .ant-tree-list-scrollbar-vertical .ant-tree-list-scrollbar-thumb {
        background-color: ${token.colorFill} !important;
        transition: background-color 0.1s ease;
      }
      .ant-tree .ant-tree-node-content-wrapper.ant-tree-node-selected {
        background-color: ${token.colorPrimaryBgHover} !important;
      }

      .ant-tree-treenode.chat2db-data-source-identity-node.ant-tree-treenode-selected,
      .ant-tree-treenode.chat2db-data-source-identity-node:has(.ant-tree-node-selected) {
        background-color: ${token.colorPrimaryBgHover};
      }

      @media (forced-colors: active) {
        .ant-tree-treenode.chat2db-data-source-identity-node {
          background-color: Canvas;
        }

        .ant-tree-treenode.chat2db-data-source-identity-node::before {
          background-color: CanvasText;
          box-shadow: none;
        }
      }
    `,
    horizontalScrolling: css`
      .ant-tree-list-scrollbar-horizontal {
        opacity: 1;
      }
    `,
    spinBox: css`
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100%;
    `,
    switcherIcon: css`
      color: ${token.colorTextQuaternary};
    `,
    unfoldSwitcherIcon: css`
      transform: rotate(90deg);
    `,
  };
});
