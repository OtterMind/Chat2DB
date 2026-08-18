import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  buttonWithStatus: css`
    position: relative;
    display: inline-flex;
    line-height: 0;
  `,
  notificationCount: css`
    position: absolute;
    z-index: 2;
    top: 1px;
    right: 1px;
    min-width: 14px;
    height: 14px;
    box-sizing: border-box;
    padding: 0 3px;
    border-radius: 7px;
    color: ${token.colorWhite};
    background: ${token.colorError};
    box-shadow: 0 0 0 1px ${token.colorBgBase};
    font-size: 9px;
    font-weight: 600;
    line-height: 14px;
    text-align: center;
    pointer-events: none;
  `,
  runningIndicator: css`
    position: absolute;
    z-index: 1;
    right: 1px;
    bottom: 1px;
    padding: 1px;
    box-sizing: border-box;
    border-radius: 50%;
    color: ${token.colorPrimary};
    background: ${token.colorBgBase};
    pointer-events: none;
    animation: workspace-task-running-spin 1.2s linear infinite;

    @keyframes workspace-task-running-spin {
      to {
        transform: rotate(360deg);
      }
    }
  `,
}));
