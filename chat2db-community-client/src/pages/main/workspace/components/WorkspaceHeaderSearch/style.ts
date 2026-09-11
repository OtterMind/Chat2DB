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
  searchSuffix: css`
    display: inline-flex;
    align-items: center;
    gap: 2px;
  `,
  closeButton: css`
    display: inline-flex;
    width: 18px;
    height: 18px;
    padding: 0;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: ${token.borderRadiusSM}px;
    color: ${token.colorTextTertiary};
    background: transparent;
    cursor: pointer;

    &:hover {
      color: ${token.colorText};
      background: ${token.colorFillSecondary};
    }
  `,
  iconButton: css`
    flex-shrink: 0;
    color: ${token.colorTextTertiary};

    &:hover {
      color: ${token.colorTextSecondary};
    }
  `,
}));
