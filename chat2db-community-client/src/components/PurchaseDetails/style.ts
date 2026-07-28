import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    purchaseDetails: css`
      display: flex;
      flex-direction: column;
      height: 100%;
      gap: 36px;
      box-sizing: border-box;
    `,
    withoutTitle: css`
      padding-top: 24px;
    `,
    title: css`
      font-size: 28px;
      line-height: 34px;
    `,
    antdTableBox: css`
      flex: 1;
      height: 0px;
    `,
    apiKeyBox: css`
      display: flex;
      gap: 4px;
    `,
    apiKeyText: css`
      flex: 1;
      width: 0px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    iconButton: css`
      flex-shrink: 0;
    `,
    validBox: css`
      display: flex;
      align-items: center;
      gap: 4px;
    `,
    valid: css`
      color: ${token.colorSuccess};
    `,
    invalid: css`
      color: ${token.colorError};
    `,

    cerTitle: css`
      display: flex;
      align-items: center;
      gap: 6px;
      &::before {
        content: '';
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background-color: ${token.colorSuccess};
      }
    `,
    monitorOff: css`
      flex-shrink: 0;
      margin-left: 4px;
      width: 16px;
    `,
    cerContent: css`
      gap: 4px;
      padding: 0 12px;
      font-size: 12px;
      color: ${token.colorTextSecondary};
    `,
    modalHeaderBox: css`
      display: flex;
      flex-direction: column;
      font-size: 16px;
      gap: 12px;
    `,
    modalHeaderTitle: css`
      display: flex;
      align-items: center;
      gap: 12px;
      font-size: 18px;
    `,
    modalHeaderSubTitle: css`
      font-size: 13px;
      color: ${token.colorTextSecondary};
      padding-left: 36px;
    `,
    modalContentBox: css`
      padding: 20px 0px;
    `,
    checkbox: css`
      color: ${token.colorTextTertiary};
      font-size: ${token.fontSizeSM}px;
    `,
    unsubscribeReasonItem: css`
      display: flex;
      align-items: center;
      font-size: 14px;
      gap: 4px;
    `,
    unsubscribeReasonIcon: css`
      color: ${token.colorErrorText};
    `,
    modalContentList: css`
      display: flex;
      flex-direction: column;
      gap: 4px;
    `,
    modalContentTitle: css`
      font-size: 16px;
      font-weight: 700;
      margin-bottom: 10px;
    `,
    questionMarkCircle: css`
      cursor: pointer;
      color: ${token.colorTextTertiary};
      &:hover {
        color: ${token.colorPrimary};
      }
    `,
    footerActions: css`
      display: flex;
      justify-content: flex-end;
      flex-shrink: 0;
      gap: 8px;
    `,
  };
});
