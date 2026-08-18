import { createStyles } from 'antd-style';

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
      z-index: 10001;
    `,
    windowsAppBar: css`
      height: 34px;
    `,
    communityAppBar: css`
      height: 36px;
    `,
    communityActions: css`
      display: flex;
      align-items: center;
      flex: 1;
      min-width: 0;
      height: 100%;
      padding: 0 8px;
      box-sizing: border-box;
      z-index: 1;
    `,
    communityMacWindowedActions: css`
      padding-left: 78px;
    `,
    communityWindowsDesktopActions: css`
      padding-right: 144px;
    `,
    communityMenu: css`
      position: absolute;
      top: 0;
      left: 4px;
      z-index: 2;
      display: flex;
      align-items: center;
      height: 100%;
      -webkit-app-region: no-drag;
    `,
    communityMenuContent: css`
      display: flex;
      align-items: center;
      gap: 2px;
      height: 100%;
      -webkit-app-region: no-drag;
    `,
    communityMenuLogo: css`
      display: block;
      width: 30px;
      height: 30px;
      border-radius: 6px;
      object-fit: contain;
      flex-shrink: 0;
    `,
    communityMenuBar: css`
      display: flex;
      align-items: center;
      gap: 2px;
      height: 100%;
    `,
    communityMenuItem: css`
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
    communityLogoContainer: css`
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
    communityAppName: css`
      font-size: 14px;
      line-height: 36px;
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
