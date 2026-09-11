import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => {
  return {
    tableBox: css`
    `,
    fillScrollBody: css`
      .ant-table-body {
        height: var(--chat2db-table-scroll-y);
        overflow-y: scroll !important;
      }
    `,
  };
});
