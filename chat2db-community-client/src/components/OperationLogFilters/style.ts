import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  filters: css`
    display: flex;
    flex: 0 0 auto;
    flex-wrap: wrap;
    align-items: center;
    align-content: flex-start;
    gap: 8px;
    box-sizing: border-box;
    width: 100%;
    min-width: 0;
  `,
  scopeFilter: css`
    min-width: 120px;
    flex: 1 1 140px;
  `,
  searchFilter: css`
    min-width: 160px;
    flex: 2 1 220px;
  `,
}));
