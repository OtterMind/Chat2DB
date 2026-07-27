import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    explorer: css`
      display: flex;
      flex: 1;
      min-height: 0;
      overflow: hidden;
      flex-direction: column;
    `,
    searchWrap: css`
      display: flex;
      align-items: center;
      flex-shrink: 0;
      height: 42px;
      padding: 0 10px;
      border-bottom: 1px solid ${token.colorBorderLayout};
      box-sizing: border-box;
    `,
    searchBar: css`
      width: 100%;
      max-width: 100%;
      background-color: ${token.colorFillTertiary};
      height: 25px;
    `,
    splitPaneContainer: css`
      position: relative;
      flex: 1;
      min-height: 0;
      overflow: hidden;
    `,
    fileSection: css`
      height: 100%;
      min-height: 0;
      overflow: hidden;
    `,
    sessionSection: css`
      display: flex;
      height: 100%;
      min-height: 0;
      flex-direction: column;
      padding: 8px 8px 10px;
      box-sizing: border-box;
      overflow: hidden;
    `,
    sectionHeader: css`
      display: flex;
      align-items: center;
      gap: 6px;
      height: 24px;
      padding: 0 4px;
      font-size: 12px;
      font-weight: 600;
      color: ${token.colorTextTertiary};
      text-transform: uppercase;
      letter-spacing: 0;
    `,
    sectionCount: css`
      color: ${token.colorTextQuaternary};
      font-weight: 500;
    `,
    sessionList: css`
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      overflow-x: hidden;
    `,
    sessionRow: css`
      display: flex;
      align-items: center;
      width: 100%;
      min-height: 34px;
      border: none;
      border-radius: 6px;
      padding: 5px 8px;
      background: transparent;
      color: ${token.colorText};
      cursor: pointer;
      text-align: left;
      transition: background-color 0.15s;

      &:hover {
        background: ${token.colorFillTertiary};
      }
    `,
    sessionRowActive: css`
      background: ${token.colorPrimaryBg};
      box-shadow: inset 2px 0 0 ${token.colorPrimary};

      &:hover {
        background: ${token.colorPrimaryBgHover};
      }
    `,
    sessionIcon: css`
      flex-shrink: 0;
      margin-right: 8px;
      color: ${token.colorTextSecondary};
    `,
    sessionMain: css`
      display: flex;
      min-width: 0;
      flex: 1;
      flex-direction: column;
      gap: 1px;
    `,
    sessionTitle: css`
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 13px;
      line-height: 18px;
      color: ${token.colorText};
    `,
    sessionContext: css`
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 11px;
      line-height: 14px;
      color: ${token.colorTextTertiary};
    `,
    emptyText: css`
      padding: 6px 8px;
      font-size: 12px;
      color: ${token.colorTextTertiary};
    `,
    contextMenu: css`
      min-width: 180px;
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
    `,
  };
});
