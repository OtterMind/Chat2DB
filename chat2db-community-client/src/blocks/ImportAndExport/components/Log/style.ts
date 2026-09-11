import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    log: css`
      position: relative;
      display: flex;
      height: clamp(260px, 38vh, 420px);
      min-width: 0;
      min-height: 0;
      flex-direction: column;
    `,
    loading: css`
      display: flex;
      height: clamp(260px, 38vh, 420px);
      min-height: 0;
      flex-direction: column;
      gap: 12px;
      align-items: center;
      justify-content: center;
      padding: 28px 24px;
      color: ${token.colorTextSecondary};
    `,
    olderLoading: css`
      position: absolute;
      z-index: 2;
      top: 8px;
      left: 50%;
      display: flex;
      gap: 6px;
      align-items: center;
      padding: 4px 10px;
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 4px;
      color: ${token.colorTextSecondary};
      background: ${token.colorBgElevated};
      box-shadow: ${token.boxShadowTertiary};
      font-size: 12px;
      transform: translateX(-50%);
    `,
    progressPanel: css`
      flex: none;
      padding: 8px 16px;
      border-top: 1px solid ${token.colorBorderSecondary};
      background: ${token.colorBgElevated};
    `,
    progressHeader: css`
      display: flex;
      width: 100%;
      gap: 12px;
      align-items: center;
      min-width: 0;
    `,
    progressValue: css`
      flex: none;
      color: ${token.colorPrimary};
      font-size: 14px;
      font-variant-numeric: tabular-nums;
      font-weight: 600;

      &[data-status='SUCCESS'] {
        color: ${token.colorSuccess};
      }

      &[data-status='FAILED'] {
        color: ${token.colorError};
      }

      &[data-status='CANCELLED'] {
        color: ${token.colorTextSecondary};
      }
    `,
    progressBar: css`
      min-width: 80px;
      flex: 1;
      line-height: 1;

      :global(.ant-progress-inner),
      :global(.ant-progress-bg) {
        border-radius: 999px;
      }
    `,
    eventConsole: css`
      display: flex;
      min-height: 0;
      flex: 1;
      flex-direction: column;
      padding: 10px 0 18px;
      color: ${token.colorText};
      background: ${token.colorBgContainer};
      font-family: Menlo, Monaco, Consolas, 'Courier New', monospace;
      font-size: 12px;
      line-height: 1.65;
    `,
    virtualListContainer: css`
      min-height: 0;
      flex: 1;
    `,
    virtualList: css`
      height: 100%;
    `,
    virtualListItem: css`
      padding: 0 14px;
    `,
    loadFailed: css`
      flex: none;
      padding: 8px 14px 0;
      color: ${token.colorWarningText};
      text-align: center;
    `,
  };
});
