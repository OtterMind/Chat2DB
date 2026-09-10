import { createStyles } from 'antd-style';

import {
  COMMUNITY_MAIN_ACTION_BAR_WIDTH,
  COMMUNITY_MAIN_ACTION_BUTTON_SIZE,
  COMMUNITY_TITLE_BAR_HEIGHT,
} from '@/constants/mainLayout';

export const useStyles = createStyles(({ css, token }) => {
  return {
    appBar: css`
      position: relative;
      flex-shrink: 0;
      display: flex;
      justify-content: space-between;
      align-items: center;
      height: 30px;
      background-color: ${token.colorBgLayout};
      border-bottom: 1px solid ${token.colorBorderLayout};
      user-select: none;
      -webkit-app-region: drag;
      /* Stay above workspace content (max in-flow z-index is 100) but below the
         antd popup layer (modals/masks render at z-index 1000), so window
         controls cannot float above confirmation dialogs. Dropdown menus
         opened from the bar are unaffected: they portal to document.body and
         global.ts forces .ant-dropdown to z-index 11000. */
      z-index: 999;
    `,
    windowsAppBar: css`
      height: 34px;
    `,
    integratedAppBar: css`
      height: ${COMMUNITY_TITLE_BAR_HEIGHT}px;
    `,
    titleBarActions: css`
      position: absolute;
      top: 0;
      right: 0;
      display: flex;
      align-items: center;
      justify-content: flex-end;
      min-width: 0;
      max-width: calc(50% - 12px);
      height: 100%;
      padding: 0 8px;
      box-sizing: border-box;
      z-index: 1;
    `,
    windowsDesktopTitleBarActions: css`
      right: 144px;
    `,
    desktopMenu: css`
      position: absolute;
      top: 0;
      left: ${(COMMUNITY_MAIN_ACTION_BAR_WIDTH - COMMUNITY_MAIN_ACTION_BUTTON_SIZE.boxSize) / 2}px;
      z-index: 2;
      display: flex;
      align-items: center;
      height: 100%;
      -webkit-app-region: no-drag;
    `,
    desktopMenuContent: css`
      display: flex;
      align-items: center;
      gap: 2px;
      height: 100%;
      -webkit-app-region: no-drag;
    `,
    desktopMenuLogoSlot: css`
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: ${COMMUNITY_MAIN_ACTION_BUTTON_SIZE.boxSize}px;
      height: ${COMMUNITY_MAIN_ACTION_BUTTON_SIZE.boxSize}px;
      flex-shrink: 0;
    `,
    desktopMenuLogo: css`
      display: block;
      width: 20px;
      height: 20px;
      border-radius: 4px;
      object-fit: contain;
    `,
    desktopMenuBar: css`
      display: flex;
      align-items: center;
      gap: 2px;
      height: 100%;
    `,
    desktopMenuItem: css`
      display: inline-flex;
      align-items: center;
      height: 30px;
      padding: 0 9px;
      border: 0;
      border-radius: 4px;
      color: ${token.colorText};
      background: transparent;
      font: inherit;
      cursor: pointer;

      &:hover,
      &:focus-visible {
        color: ${token.colorPrimary};
        background-color: ${token.controlItemBgHover};
        outline: none;
      }
    `,
    logoContainer: css`
      display: flex;
      justify-content: center;
      align-items: center;
      padding-left: 12px;
      flex: 1;
    `,
    integratedLogoContainer: css`
      position: absolute;
      inset: 0;
      padding: 0;
      pointer-events: none;
      z-index: 0;
    `,
    appName: css`
      font-weight: bold;
      text-align: center;
      -webkit-app-region: no-drag;
    `,
    integratedAppName: css`
      font-size: 14px;
      line-height: ${COMMUNITY_TITLE_BAR_HEIGHT}px;
      font-weight: 600;
      -webkit-app-region: drag;

      @media (max-width: 720px) {
        display: none;
      }
    `,
    dropdown: css`
      -webkit-app-region: no-drag;
    `,
    logoRightSolt: css`
      display: flex;
      align-items: center;
    `,

    windowsActionBar: css`
      position: absolute;
      top: 0;
      right: 0;
      display: flex;
      width: 144px;
      height: 100%;
      z-index: 2;
      -webkit-app-region: no-drag;
    `,
    windowsAction: css`
      display: flex;
      justify-content: center;
      align-items: center;
      width: 48px;
      height: 100%;
      padding: 0;
      border: 0;
      border-radius: 0;
      color: ${token.colorText};
      background: transparent;
      cursor: pointer;

      svg {
        display: block;
      }

      &:hover {
        background-color: ${token.controlItemBgHover};
      }
    `,
    closeAction: css`
      &:hover {
        background-color: ${token.colorError};
        color: ${token.colorWhite};
      }
    `,
  };
});
