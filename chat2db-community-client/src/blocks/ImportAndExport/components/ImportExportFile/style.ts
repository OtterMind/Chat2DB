import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => {
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
      padding-top: 20px;
    `,
    fileSettings: css`
      display: grid;
      grid-template-columns: minmax(0, 1fr) 180px;
      gap: 16px;
      flex: none;

      @media (max-width: 600px) { grid-template-columns: minmax(0, 1fr) 120px; }
    `,
    fillHeight: css`
      display: flex;
      flex: 1;
      min-height: 0;
      flex-direction: column;
    `,
    fileField: css`
      flex: 1;
      min-height: 226px;
      margin-bottom: 0;

      .ant-form-item-row,
      .ant-form-item-control,
      .ant-form-item-control-input,
      .ant-form-item-control-input-content {
        height: 100%;
      }
    `,
    exportCsvField: css`
      display: flex;
      min-width: 0;
      flex-direction: column;
      gap: 4px;
    `,
    customCharacterInput: css`
      padding: 4px 8px;
    `,
  };
});
