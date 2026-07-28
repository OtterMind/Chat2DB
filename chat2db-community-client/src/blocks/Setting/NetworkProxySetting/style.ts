import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  titleWithHelp: css`
    display: inline-flex;
    align-items: center;
    gap: 8px;
  `,
  form: css`
    max-width: 680px;
  `,
  helpIcon: css`
    color: ${token.colorTextTertiary};
    cursor: help;
    vertical-align: text-bottom;
  `,
  modeItem: css`
    margin-bottom: 24px;
  `,
  modeGroup: css`
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 30px;

    :global(.ant-radio-wrapper) {
      margin-inline-end: 0;
      align-items: center;

      > span:last-child {
        display: inline-flex;
        align-items: center;
      }
    }
  `,
  row: css`
    display: grid;
    grid-template-columns: minmax(0, 1fr) 160px;
    gap: 16px;

    @container (max-width: 480px) {
      grid-template-columns: minmax(0, 1fr);
    }
  `,
  actions: css`
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
  `,
  restartTip: css`
    color: ${token.colorWarning};
  `,
}));
