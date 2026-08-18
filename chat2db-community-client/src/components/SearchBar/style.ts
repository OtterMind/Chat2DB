import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  searchBar: css`
    && {
      border-radius: 6px;

      input {
        font-size: 13px;
        line-height: 24px;
        letter-spacing: 0;
      }

      input::placeholder {
        font-size: 13px;
        line-height: 24px;
        letter-spacing: 0;
        transform: none;
      }
    }
  `,
}));
