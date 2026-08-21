import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    versionsInfo: css`
      display: flex;
      align-items: center;
      padding-bottom: 10px;
      border-bottom: 1px solid ${token.colorBorder};
      margin-bottom: 10px;
    `,
    brandLogo: css`
      margin-right: 15px;
      flex-shrink: 0;
    `,
    currentVersion: css`
      font-size: 20px;
      line-height: 24px;
    `,
    appName: css`
      margin-right: 10px;
    `,
    newVersion: css`
      color: ${token.colorTextTertiary};
      font-size: 16px;
      margin-top: 4px;
      display: flex;
      gap: 10px;
      &:hover{
        cursor: pointer;
        color: ${token.colorPrimary};
      }
    `,
    buildTime: css`
      color: ${token.colorTextTertiary};
      font-size: 14px;
      margin-top: 4px;
      display: flex;
      gap: 10px;
    `,
    updateButton: css`
      margin-top: 6px;
      display: flex;
      gap: 20px;
    `,
    updateButtonFirstChild: css`
      margin-right: 20px;
    `,
    downloadProgress: css`
      width: 300px;
    `,
    updateRule: css`
      margin-bottom: 20px;
    `,
    updateRuleTitle: css`
      font-size: 16px;
      margin-bottom: 10px;
    `,
    updateRuleGroup: css`
      display: flex;
      flex-direction: column;
    `,
    updateRuleRadioContent: css`
      display: flex;
      align-items: center;
    `,
    updateRuleRadioContentIcon: css`
      margin-left: 4px;
      color: ${token.colorTextTertiary};
      font-size: 12px;
    `,
    holdingService: css`
      margin-top: 20px;
    `,
    brief: css`
      display: flex;
      flex-direction: column;
      align-items: center;
    `,
    env: css`
      margin: 4px 0px;
    `,
    version: css`
      margin: 4px 0px;
    `,
    log: css`
      margin-top: 4px;
      color: ${token.colorPrimary};
      cursor: pointer;

      &:hover {
        text-decoration: underline;
      }
    `,
    checkboxBox: css`
      display: flex;
      flex-direction: column;
      gap: 10px;
    `,
  };
});
