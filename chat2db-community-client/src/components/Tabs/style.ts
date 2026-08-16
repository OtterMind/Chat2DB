import { createStyles } from 'antd-style';

export const useStyles = createStyles(
  (
    { css, cx, token },
    {
      height,
      showAddButton,
      showTabBarExtraContent,
      tabMaxWidth,
      uniformTabWidth,
    }: {
      height: number;
      showAddButton: boolean;
      showTabBarExtraContent: boolean;
      tabMaxWidth: any;
      uniformTabWidth: boolean;
    },
  ) => {
    const { colorBgBase, colorPrimary, colorText } = token;
    const colorBorderLayout = (token as any).colorBorderLayout || token.colorBorderSecondary;
    const moreTabsWidth = showTabBarExtraContent ? 29 : 34;
    const tabBarExtraWidth = showTabBarExtraContent ? 29 : 0;
    const activeTab = css`
      &:after {
        content: '';
        position: absolute;
        left: 0px;
        right: 0px;
        top: 0px;
        height: 2px;
        background-color: ${colorPrimary};
        z-index: 2;
      }
      &:before {
        content: '';
        position: absolute;
        left: 0px;
        right: 0px;
        bottom: -1px;
        height: 3px;
        background-color: ${colorBgBase};
        z-index: 2;
      }
    `;
    const tabItemLastChildBorderConceal = css`
      &:last-child {
        border-right: none;
      }
    `;
    const tabItemIcon = cx(css`
      flex-shrink: 0;
      margin: 0px 4px;
      &:hover {
        color: ${token.colorPrimaryText};
        background-color: ${token.colorFillTertiary};
      }
    `);
    return {
      popoverOverlay: css`
        .ant-popover-inner {
          padding: 0 !important;
        }
        & pre {
          border-radius: 8px;
        }
      `,
      box: css``,
      tabItemIcon,
      tabBox: css`
        display: flex;
        flex-direction: column;
      `,
      placeholderTabItemIcon: css`
        width: 20px;
      `,
      tabsNav: css`
        position: relative;
        display: flex;
        overflow: hidden;
        height: ${height}px;
        line-height: ${height}px;
        flex-shrink: 0;
        background-color: ${token.colorFillQuaternary};

        &:before {
          content: '';
          position: absolute;
          left: 0px;
          right: 0px;
          bottom: 0px;
          height: 1px;
          background-color: ${colorBorderLayout};
          z-index: 2;
        }
      `,
      tabPaneDropOver: css`
        &:after {
          content: '';
          position: absolute;
          left: 0px;
          right: 0px;
          bottom: 0px;
          height: 2px;
          background-color: ${colorPrimary};
          z-index: 4;
          pointer-events: none;
        }
      `,
      tabList: css`
        display: flex;
        position: relative;
        max-width: calc(
          100% - ${moreTabsWidth + (showAddButton ? 34 : 0) + tabBarExtraWidth}px
        );
        width: fit-content;
        flex-shrink: 0;
        overflow-x: auto;
        overflow-y: hidden;
        scrollbar-color: auto;
        /* &:before {
          content: '';
          position: absolute;
          left: 0px;
          right: 0px;
          bottom: 0px;
          height: 1px;
          background-color: ${colorBorderLayout};
          z-index: 2;
        } */

        &::-webkit-scrollbar {
          width: 4px;
          height: 4px;
        }

        &::-webkit-scrollbar-thumb {
          border-radius: 0px !important;
          background-color: transparent;
          background-clip: padding-box;
        }

        &:hover::-webkit-scrollbar-thumb {
          background-color: ${token.colorFillSecondary};
        }

        &::-webkit-scrollbar-thumb:hover {
          background-color: ${token.colorFill};
        }

        &::-webkit-scrollbar-corner {
          display: none;
        }
      `,
      tabItem: css`
        position: relative;
        display: flex;
        align-items: center;
        ${uniformTabWidth
          ? `
            box-sizing: border-box;
            flex: 0 0 ${tabMaxWidth};
            width: ${tabMaxWidth};
            max-width: ${tabMaxWidth};
          `
          : ''}
        padding-left: 10px;
        cursor: pointer;
        user-select: none;
        border-right: 1px solid ${colorBorderLayout};
        ${showAddButton ? tabItemLastChildBorderConceal : ''}

        &:hover {
          color: ${colorPrimary};
          .${tabItemIcon} {
            opacity: 1;
          }
        }
      `,
      draggingTab: css`
        opacity: 0.45;
      `,
      tabItemTextBox: css`
        flex: 1;
        display: flex;
        align-items: center;
        min-width: 0;
      `,
      tabItemText: css`
        flex: 1;
        min-width: 0;
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
        max-width: ${tabMaxWidth};
      `,
      rightBox: css`
        flex: 1;
        flex-shrink: 0;
        background-color: ${token.colorFillQuaternary};
        border-bottom: 1px solid ${colorBorderLayout};
        display: flex;
        z-index: 3;
      `,
      addIcon: css`
        width: 34px;
        height: 100%;
        display: flex;
        justify-content: center;
        align-items: center;
        cursor: pointer;
          border-left: 1px solid ${colorBorderLayout};
        box-sizing: border-box;

        /* &:hover {
          color: ${colorPrimary};
          background-color: ${token.colorPrimaryBgHover};
        } */
      `,
      addIconDisabled: css`
        color: ${token.colorTextDisabled};
        cursor: not-allowed;
        &:hover {
          color: ${token.colorTextDisabled};
        }
      `,
      input: css`
        border: 0;
        width: 200px;
        flex: 1;
        height: 20px;
        outline: none;
        font-size: 14px;
        font-weight: 400;
        background-color: transparent;
        color: ${colorText};
        input:focus {
          outline: none;
        }
      `,
      prefixIcon: css`
        flex-shrink: 0;
        margin-right: 4px;
      `,
      pinnedIcon: css`
        flex-shrink: 0;
        width: 18px;
        margin-right: 4px;
        color: ${token.colorTextTertiary};
        font-size: 14px;
        line-height: 1;
      `,
      activeTab: css`
        position: relative;
        background-color: ${token.colorBgBase};
        color: ${token.colorText};
        ${activeTab}
        .${tabItemIcon} {
          opacity: 1;
        }
      `,
      tabsContent: css`
        flex: 1;
        height: 0px;
        min-height: 0;
        overflow: hidden;
      `,
      tabsContentItem: css`
        height: 100%;
        width: 100%;
        min-height: 0;
        overflow: hidden;
        display: none;
        position: relative;
      `,
      tabsContentItemActive: css`
        display: block;
      `,
      moreTabs: css`
        width: ${moreTabsWidth}px;
        flex-shrink: 0;
        height: 100%;
        display: flex;
        justify-content: ${showTabBarExtraContent ? 'flex-start' : 'center'};
        align-items: center;
      `,
      tabBarExtra: css`
        width: ${tabBarExtraWidth}px;
        flex-shrink: 0;
        height: 100%;
        display: flex;
        justify-content: flex-end;
        align-items: center;
      `,
      moreTabsBox: css`
        display: flex;
        flex-direction: column;
        background-color: ${token.colorBgElevated};
        border-radius: 8px;
        padding: 4px;
        list-style-type: none;
        border: 1px solid ${token.colorBorderSecondary};
        min-width: 220px;
        max-height: 50vh;
      `,
      moreTabsMenu: css`
        overflow: auto;
        height: 0px;
        flex: 1;
      `,
      moreTabsMenuItem: css`
        padding: 5px 0px 5px 8px;
        display: flex;
        align-items: center;
        border-radius: 4px;
        cursor: pointer;
        &:hover {
          background-color: ${token.colorFillQuaternary};
          color: ${token.colorPrimary};
        }
      `,
      moreTabsMenuItemText: css`
        flex: 1;
      `,
      moreTabsMenuItemActive: css`
        background-color: ${token.colorFillTertiary};
        color: ${token.colorPrimary};
      `,
      tabItemIconActive: css`
        opacity: 1 !important;
        color: ${token.colorPrimaryText};
        &:hover {
          background-color: ${token.colorFillSecondary};
        }
      `,
      searchBar: css`
        flex-shrink: 0;
        display: flex;
        align-items: center;
        padding: 0px 6px;
        height: 26px;
        display: flex;
        border-bottom: 1px solid ${colorBorderLayout};
        input {
          flex: 1;
          height: 100%;
          /* Remove all Ant Design input styles. */
          padding: 0px 2px;
          border: 0;
          outline: none;
          box-shadow: none !important;
          background: none;
          color: ${token.colorText};
        }
      `,
      iconContainer: css`
        flex-shrink: 0;
        height: 100%;
        display: flex;
        justify-content: center;
        align-items: center;
        margin-right: 2px;
      `,
      noMatch: css`
        padding: 6px 0px 10px;
        text-align: center;
        color: ${token.colorTextSecondary};
      `,
    };
  },
);
