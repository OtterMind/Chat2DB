import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  wizard: css`
    min-width: 0;
    padding-top: 20px;
  `,
  stepBody: css`
    height: clamp(300px, 58vh, 620px);
    padding: 20px 2px 8px;
    overflow-y: auto;
  `,
  section: css`
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 16px 0;
    border-top: 1px solid ${token.colorBorderSecondary};

    &:first-of-type {
      padding-top: 0;
      border-top: 0;
    }
  `,
  sectionHeader: css`
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;

    > button {
      margin-inline-start: auto;
    }
  `,
  sectionTitle: css`
    margin-bottom: 10px;
    color: ${token.colorText};
    font-size: 14px;
    font-weight: 600;
  `,
  sourceTable: css`
    margin-top: 12px;

    .ant-select {
      width: 100%;
    }
  `,
  switchGrid: css`
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px 16px;

    @media (max-width: 680px) {
      grid-template-columns: minmax(0, 1fr);
    }
  `,
  switchRow: css`
    display: flex;
    min-height: 32px;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  `,
  checkboxGrid: css`
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px 16px;

    @media (max-width: 680px) {
      grid-template-columns: minmax(0, 1fr);
    }
  `,
  dependencyRow: css`
    display: grid;
    grid-template-columns:
      minmax(130px, 1fr) minmax(120px, 1fr) minmax(130px, 1fr) minmax(120px, 1fr)
      minmax(104px, auto);
    gap: 8px;
    align-items: center;

    @media (max-width: 780px) {
      grid-template-columns: minmax(0, 1fr) minmax(104px, auto);

      .ant-select {
        grid-column: 1;
      }

      > div:last-child {
        grid-column: 2;
        grid-row: 1;
      }
    }
  `,
  dependencyActions: css`
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 2px;

    .ant-tag {
      margin-inline-end: 0;
    }
  `,
  emptyHint: css`
    padding: 20px 0;
    color: ${token.colorTextTertiary};
    text-align: center;
  `,
  inlineControls: css`
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    gap: 12px;

    .ant-select,
    .ant-input-number {
      width: min(240px, 100%);
    }
  `,
  fieldWithError: css`
    display: flex;
    width: min(240px, 100%);
    flex-direction: column;
    gap: 4px;

    .ant-input-number {
      width: 100%;
    }
  `,
  fieldError: css`
    color: ${token.colorError};
    font-size: ${token.fontSizeSM}px;
    line-height: ${token.lineHeightSM};
  `,
  samplePercent: css`
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  `,
  navigation: css`
    display: flex;
    min-height: 40px;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    padding-top: 12px;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,
}));
