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
      z-index: 1;
    `,
    communityMacWindowedActions: css`
      padding-left: 78px;
    `,
    communityWindowsDesktopActions: css`
      padding-right: 144px;
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
      display: flex;
      -webkit-app-region: no-drag;
    `,
    windowsAction: css`
      width: 34px !important;
      height: 34px !important;
      border-radius: 0px !important;
      display: flex;
      justify-content: center;
      align-items: center;
      cursor: pointer;
      i {
        font-size: 14px;
      }
      &:hover {
        background-color: ${token.controlItemBgHover};
      }
    `,
    closeAction: css`
      &:hover {
        background-color: ${token.colorError};
        color: #fff;
      }
    `,
  };
});
