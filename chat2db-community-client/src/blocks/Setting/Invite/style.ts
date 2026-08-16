import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    wrapper: css`
      min-width: 0;
      padding-top: 24px;
    `,
    inviteToolbar: css`
      min-height: 52px;
      padding-bottom: 20px;
      border-bottom: 1px solid ${token.colorBorderSecondary};
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 20px;

      @container (max-width: 820px) {
        align-items: flex-start;
        flex-direction: column;
      }
    `,
    inviteCodeMeta: css`
      display: flex;
      align-items: center;
      gap: 4px;
    `,
    sectionTitle: css`
      font-size: ${token.fontSizeLG}px;
      font-weight: ${token.fontWeightStrong};
    `,
    toolbarActions: css`
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 10px;

      @container (max-width: 820px) {
        width: 100%;
        justify-content: flex-start;
        flex-wrap: wrap;
      }
    `,
    codeValue: css`
      min-height: 32px;
      padding: 0 4px 0 10px;
      border: 1px solid ${token.colorBorder};
      border-radius: 6px;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: ${token.colorBgContainer};
      font-family: ${token.fontFamilyCode};
    `,

    amountWrapper: css`
      padding: 24px 0;
      border-bottom: 1px solid ${token.colorBorderSecondary};
      display: grid;
      grid-template-columns: repeat(5, minmax(0, 1fr));

      @container (max-width: 820px) {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        row-gap: 22px;
      }
    `,

    amountItem: css`
      min-width: 0;
      padding: 2px 16px;
      display: flex;
      flex-direction: column;
      align-items: center;
      &:not(:last-child) {
        border-right: 1px solid ${token.colorBorderSecondary};
      }

      @container (max-width: 820px) {
        border-right: 0 !important;
      }
    `,
    amountCount: css`
      overflow: hidden;
      max-width: 100%;
      font-size: 22px;
      font-weight: ${token.fontWeightStrong};
      line-height: 30px;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    amountTitle: css`
      overflow: hidden;
      max-width: 100%;
      color: ${token.colorTextSecondary};
      font-size: ${token.fontSizeSM}px;
      line-height: 22px;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    inviteListWrapper: css`
      min-width: 0;
      padding-top: 28px;
    `,
    inviteTitle: css`
      font-size: ${token.fontSizeLG}px;
      font-weight: ${token.fontWeightStrong};
      margin-bottom: 12px;
      display: flex;
      align-items: center;
      gap: 8px;
    `,

  };
});
