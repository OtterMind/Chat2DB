import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token, cx, prefixCls }) => {
  const taskItemHeader = cx(css`
    display: flex;
    grid-column: 1;
    grid-row: 1;
    width: 100%;
    min-width: 0;
    gap: 8px;
    align-items: center;
  `);
  const taskName = cx(css`
    flex: 1;
    width: 0px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `);
  return {
    taskName,
    taskStatusIcon: css`
      display: inline-flex;
      flex: none;
      align-items: center;
      justify-content: center;
      color: ${token.colorTextTertiary};

      &[data-status='RUNNING'] {
        color: ${token.colorPrimary};

        svg {
          animation: task-center-status-spin 1.2s linear infinite;
        }
      }

      &[data-status='SUCCESS'] {
        color: ${token.colorSuccess};
      }

      &[data-status='FAILED'] {
        color: ${token.colorError};
      }

      &[data-status='CANCELLED'] {
        color: ${token.colorTextSecondary};
      }

      @keyframes task-center-status-spin {
        to {
          transform: rotate(360deg);
        }
      }
    `,
    notification: css`
      .${prefixCls}-popover-inner {
        padding: 0;
      }
    `,
    wrapper: css`
      width: 420px;
      max-width: calc(100vw - 32px);
    `,
    title: css`
      padding: 8px 10px;
      font-weight: ${token.fontWeightStrong};
      font-size: ${token.fontSizeLG}px;
      border-bottom: 1px solid ${token.colorBorderSecondary};
      display: flex;
      align-items: center;
      justify-content: space-between;
    `,
    listWrapper: css`
      padding: 0 8px;
      max-height: 400px;
      overflow-y: auto;
      overflow-x: hidden;
    `,
    loadMoreIndicator: css`
      display: flex;
      height: 28px;
      align-items: center;
      justify-content: center;
    `,
    listItem: css`
      position: relative;
      padding: 6px 0px;
      cursor: pointer;
      border-bottom: 1px solid ${token.colorBorderSecondary};
      transition: background-color 0.2s ease;

      &[data-highlighted='true'] {
        margin: 0 -8px;
        padding: 6px 8px;
        background: ${token.colorSuccessBg};

        &::before {
          position: absolute;
          inset: 4px auto 4px 0;
          width: 3px;
          border-radius: 0 2px 2px 0;
          background: ${token.colorSuccess};
          content: '';
        }
      }

      &[data-highlighted='true'][data-status='FAILED']::before {
        background: ${token.colorError};
      }

      &[data-highlighted='true'][data-status='FAILED'] {
        background: ${token.colorErrorBg};
      }

      &[data-highlighted='true'][data-status='CANCELLED']::before {
        background: ${token.colorTextSecondary};
      }

      &[data-highlighted='true'][data-status='CANCELLED'] {
        background: ${token.colorFillSecondary};
      }

      &:last-child {
        border-bottom: none;
      }

      &:hover .task-item-actions,
      &:focus-within .task-item-actions {
        opacity: 1;
        pointer-events: auto;
      }
    `,
    taskItemHeader,
    taskCard: css`
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      grid-template-rows: auto auto;
      column-gap: 8px;
      min-width: 0;
    `,
    listItemLeft: css`
      grid-column: 1;
      grid-row: 2;
      width: 100%;
      min-width: 0;
      display: flex;
      gap: 4px;
      align-items: center;
      margin-top: 3px;
      overflow: hidden;
      white-space: nowrap;
      font-size: 12px;
      color: ${token.colorTextSecondary};
      font-variant-numeric: tabular-nums;
    `,
    timingTooltip: css`
      display: grid;
      gap: 2px;
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
    `,
    listItemRight: css`
      display: flex;
      grid-column: 2;
      grid-row: 1;
      gap: 4px;
      align-items: center;
      align-self: center;
    `,
    taskActions: cx(
      'task-item-actions',
      css`
        display: flex;
        grid-column: 2;
        grid-row: 2;
        gap: 2px;
        align-items: center;
        align-self: center;
        justify-content: flex-end;
        opacity: 0;
        pointer-events: none;
        transition: opacity 0.16s ease;
      `,
    ),
    deleteAction: css`
      color: ${token.colorError};

      &:hover {
        color: ${token.colorError};
        background-color: ${token.colorErrorBg};
      }
    `,
  };
});
