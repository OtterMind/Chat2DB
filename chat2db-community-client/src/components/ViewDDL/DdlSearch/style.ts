import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    preview: css`
      display: flex;
      flex-direction: column;
      min-height: 0;
      min-width: 0;
      overflow: hidden;
      &:focus {
        outline: none;
      }
    `,
    viewport: css`
      flex: 1 1 auto;
      min-height: 0;
      && pre code {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        overflow: visible;
      }
    `,
    container: css`
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      align-items: center;
      gap: 4px;
      flex: 0 0 auto;
      padding: 4px 8px;
      background: ${token.colorBgElevated};
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 6px;
      box-shadow: ${token.boxShadowSecondary};
    `,
    searchBar: css`
      grid-column: 1 / -1;
      width: 100%;
      min-width: 0;
      border: 0;
      background: none;
    `,
    count: css`
      flex-shrink: 0;
      font-size: 12px;
      line-height: 12px;
      white-space: nowrap;
    `,
    noSearchResult: css`
      color: ${token.colorErrorText};
    `,
    buttonGroup: css`
      grid-column: 2;
      flex-shrink: 0;
      display: flex;
      align-items: center;
      gap: 2px;
    `,
  };
});
