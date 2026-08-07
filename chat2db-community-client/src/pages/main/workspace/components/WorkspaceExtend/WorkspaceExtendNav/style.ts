import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, prefixCls, token }) => {
  return {
    workspaceExtendNav: css`
      display: flex;
      flex-direction: column;
      align-items: center;
      width: 38px;
      padding: 8px 0px;
    `,
    topBox: css`
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      flex: 1;
    `,
    bottomBox: css`
      flex-shrink: 0;
    `,
    taskNotificationBadge: css`
      line-height: 0;

      .${prefixCls}-badge-count {
        min-width: 16px;
        height: 16px;
        padding: 0 4px;
        font-size: 10px;
        line-height: 16px;
        pointer-events: none;
      }
    `,
    taskCenterButton: css`
      position: relative;
      display: inline-flex;
    `,
    taskRunningIndicator: css`
      position: absolute;
      right: -1px;
      bottom: -1px;
      padding: 1px;
      border-radius: 50%;
      color: ${token.colorPrimary};
      background: ${token.colorBgBase};
      pointer-events: none;
      animation: task-center-running-spin 1.2s linear infinite;

      @keyframes task-center-running-spin {
        to {
          transform: rotate(360deg);
        }
      }
    `,
    aiButton: css`
      width: 32px;
      height: 32px;
      padding: 0;
      display: flex;
      align-items: center;
      justify-content: center;
    `,
    aiIconWrapper: css`
      position: relative;
      width: 32px;
      height: 32px;
    `,
    defaultImg: css`
      position: absolute;
      width: 100%;
      height: 100%;
      transition: opacity 0.3s;
    `,
    hoverImg: css`
      position: absolute;
      width: 100%;
      height: 100%;
      opacity: 0;
      transition: opacity 0.3s;

      button:hover & {
        opacity: 1;
      }
    `,
  };
});
