import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  form: css`
    max-height: 60vh;
    overflow-y: auto;
    padding-top: ${token.paddingXS}px;
  `,
  valueRow: css`
    display: flex;
    align-items: center;
    gap: ${token.marginSM}px;
  `,
  typeSelect: css`
    flex: 0 0 112px;
  `,
  valueInput: css`
    flex: 1;
    min-width: 0;
  `,
  namedLabel: css`
    font-family: ${token.fontFamilyCode};
  `,
}));
