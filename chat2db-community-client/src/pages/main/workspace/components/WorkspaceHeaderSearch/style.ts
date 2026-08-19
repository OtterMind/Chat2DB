import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  search: css`
    display: inline-flex;
    min-width: 0;
    align-items: center;
  `,
  searchExpanded: css`
    min-width: 100px;
    max-width: 220px;
    flex: 1;
  `,
  searchBar: css`
    width: 100%;
    min-width: 0;
    height: 25px;
    background-color: ${token.colorFillTertiary};
  `,
  searchMatchCount: css`
    color: ${token.colorTextQuaternary};
    font-size: 12px;
    line-height: 1;
    white-space: nowrap;
    user-select: none;
  `,
  iconButton: css`
    flex-shrink: 0;
    color: ${token.colorTextTertiary};

    &:hover {
      color: ${token.colorTextSecondary};
    }
  `,
}));
