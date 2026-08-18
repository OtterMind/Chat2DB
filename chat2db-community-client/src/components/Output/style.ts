import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    output: css`
      width: 100%;
      height: 100%;
      position: relative;
      display: flex;
      flex-direction: column;
      background: ${token.colorBgContainer};
    `,
    outputContent: css`
      padding: 8px;
      overflow-y: auto;
      flex: 1;
      height: 0px;
    `,
    outputItem: css`
      position: relative;
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 8px;
      padding: 10px 8px;
      margin-bottom: 8px;
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 6px;
      background: ${token.colorFillQuaternary};
      cursor: pointer;
      transition:
        border-color 0.16s ease,
        background-color 0.16s ease;

      &:hover {
        border-color: ${token.colorPrimaryBorder};
        background: ${token.colorFillTertiary};
      }

      &:hover .output-record-actions,
      &:focus-within .output-record-actions {
        opacity: 1;
        pointer-events: auto;
      }
    `,
    recordMain: css`
      display: flex;
      min-width: 0;
      flex: 1;
      gap: 8px;
    `,
    databaseIcon: css`
      width: 28px;
      height: 28px;
      flex: 0 0 28px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 6px;
      background: ${token.colorBgContainer};
      border: 1px solid ${token.colorBorderSecondary};
    `,
    databaseIconSvg: css`
      display: block;
    `,
    databaseFallbackIcon: css`
      color: ${token.colorTextTertiary};
    `,
    recordInfo: css`
      min-width: 0;
      flex: 1;
    `,
    datasourceLine: css`
      display: flex;
      align-items: center;
      gap: 6px;
      min-width: 0;
      height: 22px;
    `,
    datasourceName: css`
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: ${token.colorText};
      font-weight: 600;
      font-size: 13px;
    `,
    sqlScope: css`
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: ${token.colorTextSecondary};
      font-size: 12px;
      line-height: 18px;

      &::before {
        content: '/';
        color: ${token.colorTextQuaternary};
        margin-right: 6px;
      }
    `,
    sqlSummary: css`
      display: -webkit-box;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
      overflow: hidden;
      margin-top: 4px;
      color: ${token.colorTextSecondary};
      font-family: ${token.fontFamilyCode};
      font-size: 12px;
      line-height: 18px;
      word-break: break-word;
    `,
    metaLine: css`
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 4px 8px;
      margin-top: 6px;
      color: ${token.colorTextTertiary};
      font-size: 11px;
      line-height: 16px;
    `,
    statusText: css`
      color: ${token.colorSuccess};
      font-weight: 500;
    `,
    failureStatusText: css`
      color: ${token.colorError};
    `,
    recordActions: css`
      display: flex;
      align-items: center;
      gap: 2px;
      flex: 0 0 auto;
      padding-top: 1px;
      opacity: 0;
      pointer-events: none;
      transition: opacity 0.16s ease;
    `,
    actionButton: css`
      &:hover {
        color: ${token.colorPrimary};
      }
    `,
    emptyContent: css`
      padding: 40px 0;
      color: ${token.colorTextTertiary};
      text-align: center;
      font-size: 12px;
    `,
  };
});
