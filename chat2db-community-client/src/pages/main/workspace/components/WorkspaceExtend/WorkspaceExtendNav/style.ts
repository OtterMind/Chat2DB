import { createStyles } from 'antd-style';

export const useStyles = createStyles(
  ({ css, prefixCls, token }, { orientation }: { orientation: 'vertical' | 'horizontal' }) => {
    return {
      workspaceExtendNav: css`
        display: flex;
        flex-direction: ${orientation === 'horizontal' ? 'row' : 'column'};
        align-items: center;
        ${orientation === 'horizontal' ? 'height: 100%; padding: 0 2px;' : 'width: 38px; padding: 8px 0;'}
      `,
      topBox: css`
        display: flex;
        flex-direction: ${orientation === 'horizontal' ? 'row' : 'column'};
        align-items: center;
        gap: ${orientation === 'horizontal' ? '2px' : '8px'};
        flex: ${orientation === 'horizontal' ? '0 0 auto' : '1'};
      `,
      taskNotificationBadge: css`
        line-height: 0;

        &.${prefixCls}-badge .${prefixCls}-badge-count {
          min-width: 16px;
          height: 16px;
          padding: 0 4px;
          font-size: 10px;
          line-height: 16px;
          transform: translateY(-50%);
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
    };
  },
);
