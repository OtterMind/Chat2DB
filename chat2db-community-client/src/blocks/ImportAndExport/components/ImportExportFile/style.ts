import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    checkboxBody: css`
      .ant-form-item {
        margin-bottom: 0;
      }
      .ant-form-item-control-input{
        min-height: 30px;
      }
    `,
    exportLocationBox: css`
      display: flex;
      align-items: center;
      gap: 4px;
    `,
    iconButton: css`
      flex-shrink: 0;
      border-radius: 6px !important;
    `,
    form: css`
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      column-gap: 16px;
      padding-top: 20px;

      @media (max-width: 640px) {
        grid-template-columns: minmax(0, 1fr);
      }
    `,
    fullWidth: css`
      grid-column: 1 / -1;
    `,
    previewPanel: css`
      grid-column: 1 / -1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 12px;
      border: 1px solid rgba(128, 128, 128, 0.25);
      border-radius: 8px;
      margin-bottom: 16px;
    `,
    previewTitle: css`
      font-weight: 600;
    `,
    previewMeta: css`
      display: flex;
      gap: 16px;
      color: rgba(128, 128, 128, 1);
      font-size: 12px;
    `,
    previewRow: css`
      display: flex;
      align-items: center;
      gap: 8px;
      .ant-select {
        flex: 1;
      }
    `,
    previewFileColumn: css`
      width: 120px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    previewWarning: css`
      color: rgba(230, 162, 60, 1);
      font-size: 12px;
    `,
    modeControl: css`
      display: flex;
      align-items: center;
      gap: 8px;
      min-height: 22px;
    `,
    modeHelpButton: css`
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 20px;
      height: 20px;
      padding: 0;
      color: ${token.colorTextTertiary};
      cursor: help;
      background: transparent;
      border: 0;

      &:hover {
        color: ${token.colorPrimary};
      }

      &:focus-visible {
        outline: 2px solid currentColor;
        outline-offset: 2px;
      }
    `,
    modeTooltip: css`
      width: min(400px, calc(100vw - 48px));

      ul {
        padding-left: 18px;
        margin: 6px 0 0;
      }

      li + li {
        margin-top: 4px;
      }
    `,
    modeTooltipTitle: css`
      margin-top: 8px;
      font-weight: 600;
    `,
    modeIndicator: css`
      grid-column: 1 / -1;
      margin: -8px 0 12px;
      color: rgba(128, 128, 128, 0.75);
      font-size: 12px;
    `,
    admissionPanel: css`
      grid-column: 1 / -1;
      display: flex;
      flex-direction: column;
      gap: 10px;
      margin: -6px 0 12px;

      .ant-alert-description {
        white-space: pre-line;
      }
    `,
  };
});
