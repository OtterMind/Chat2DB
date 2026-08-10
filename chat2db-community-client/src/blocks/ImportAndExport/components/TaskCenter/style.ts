import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token, cx }) => {
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
    wrapper: css`
      display: flex;
      width: 100%;
      height: 100%;
      flex-direction: column;
      background: ${token.colorBgContainer};
    `,
    title: css`
      display: flex;
      align-items: center;
      justify-content: space-between;
      box-sizing: border-box;
      height: 36px;
      padding: 0 12px;
      border-bottom: 1px solid ${token.colorBorderLayout};
      font-weight: 600;
      background: ${token.colorBgContainer};
    `,
    listWrapper: css`
      flex: 1;
      height: 0;
      padding: 8px;
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
      margin-bottom: 8px;
      padding: 10px 8px;
      cursor: pointer;
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 6px;
      background: ${token.colorFillQuaternary};
      transition:
        border-color 0.16s ease,
        background-color 0.16s ease;

      &:hover {
        border-color: ${token.colorPrimaryBorder};
        background: ${token.colorFillTertiary};
      }

      &[data-highlighted='true'] {
        border-color: ${token.colorSuccessBorder};
        background: ${token.colorSuccessBg};

        &::before {
          position: absolute;
          inset: 5px auto 5px 0;
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
        border-color: ${token.colorErrorBorder};
        background: ${token.colorErrorBg};
      }

      &[data-highlighted='true'][data-status='CANCELLED']::before {
        background: ${token.colorTextSecondary};
      }

      &[data-highlighted='true'][data-status='CANCELLED'] {
        border-color: ${token.colorBorder};
        background: ${token.colorFillSecondary};
      }

      &:last-child {
        margin-bottom: 0;
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
