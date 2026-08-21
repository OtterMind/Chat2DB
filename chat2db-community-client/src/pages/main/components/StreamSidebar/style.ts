import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    streamSidebar: css`
      user-select: none;
    `,
    streamSidebarHeader: css`
      display: flex;
      align-items: center;
      gap: 8px;
      height: 52px;
      padding: 8px 12px;
      border-bottom: 1px solid ${token.colorBorderLayout};
      box-sizing: border-box;
    `,
    streamNewChatButton: css`
      flex: 1;
      height: 32px;
      border-radius: 6px;
      justify-content: center;
    `,
    streamSearchTooltipAnchor: css`
      display: inline-flex;
      flex: 0 0 32px;
      width: 32px;
      height: 32px;
    `,
    streamSearchButton: css`
      flex-shrink: 0;
      border-radius: 6px !important;
      color: ${token.colorTextSecondary};
      &:hover {
        background-color: ${token.colorFillTertiary};
      }
    `,
    streamSearchWrap: css`
      flex-shrink: 0;
      padding: 8px 12px 0;
    `,
    streamSectionTitle: css`
      display: flex;
      align-items: center;
      height: 34px;
      padding: 0 12px;
      font-size: 12px;
      color: ${token.colorTextTertiary};
      white-space: nowrap;
      flex-shrink: 0;
    `,
    streamSessionList: css`
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      overflow-x: hidden;
      padding: 0 8px 8px;
    `,
    sidebarSessionItem: css`
      position: relative;
      display: flex;
      align-items: center;
      padding: 8px 12px;
      border-radius: 8px;
      cursor: pointer;
      transition: background-color 0.15s;
      &:hover {
        background-color: ${token.colorFillTertiary};
      }
      &:hover .sidebar-session-delete {
        opacity: 1;
      }
    `,
    sidebarSessionItemActive: css`
      background-color: ${token.colorPrimaryBg};
    `,
    sidebarSessionTitle: css`
      font-size: 13px;
      color: ${token.colorText};
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      flex: 1;
      min-width: 0;
      line-height: 20px;
    `,
    sidebarSessionTime: css`
      margin-left: 8px;
      font-size: 12px;
      color: ${token.colorTextTertiary};
      white-space: nowrap;
      flex-shrink: 0;
    `,
    sessionEmpty: css`
      font-size: 13px;
      color: ${token.colorTextTertiary};
      padding: 8px 20px;
    `,
    contextMenu: css`
      min-width: 150px;
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
    `,
  };
});
