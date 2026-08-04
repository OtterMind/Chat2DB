import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    container: css`
      width: 100%;
      padding-top: 24px;
      display: flex;
      gap: 32px;

      @container (max-width: 960px) {
        flex-direction: column;
      }
    `,
    formWrapper: css`
      flex: 1;
      min-width: 0;
    `,
    settingSection: css`
      width: 100%;
      padding-bottom: 24px;

      & + & {
        padding-top: 24px;
        border-top: 1px solid ${token.colorSplit};
      }

      &:last-child {
        padding-bottom: 0;
      }
    `,
    sectionTitle: css`
      margin: 0 0 18px;
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 14px;
      font-weight: 600;
      line-height: 22px;
    `,
    sectionIcon: css`
      flex: 0 0 auto;
      color: ${token.colorPrimary};
    `,
    fieldGrid: css`
      width: 100%;
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      column-gap: 18px;

      :global(.ant-form-item) {
        min-width: 0;
        margin-bottom: 18px;
      }

      :global(.ant-select),
      :global(.ant-input-number-group-wrapper),
      :global(.ant-input-number) {
        width: 100%;
      }

      @container (max-width: 520px) {
        grid-template-columns: minmax(0, 1fr);
      }
    `,
    fullWidthField: css`
      grid-column: 1 / -1;
    `,
    editorWrapper: css`
      /* max-height: calc(100vh - 120px); */
      /* max-height: calc(100vh - 60px); */
      /* border: 1px solid ${token.colorBorderSecondary}; */
      /* padding-top: 8px; */
      position: sticky;
      top: 24px;
      flex: 1;
      min-width: 0;
      height: clamp(420px, calc(100vh - 190px), 720px);
      overflow: hidden;

      @container (max-width: 960px) {
        position: static;
        width: 100%;
        height: 420px;
        min-height: 320px;
        flex: none;
      }
    `,
  };
});
