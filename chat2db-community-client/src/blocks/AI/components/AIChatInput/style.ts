import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    chatInputArea: css`
      width: 100%;
      background-color: ${token.colorBgElevated};
      border-radius: 24px;
      box-sizing: border-box;
      padding: 12px 16px 8px;
      overflow: hidden;
      border: 1px solid ${token.colorBorder};
      box-shadow: 0 2px 8px 0 rgba(0, 0, 0, 0.06), 0 1px 2px 0 rgba(0, 0, 0, 0.04);
      transition: border-color 0.2s, box-shadow 0.2s;

      &:focus-within {
        border-color: ${token.colorPrimary};
        box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.08), 0 0 0 2px ${token.colorPrimaryBg};
      }
    `,
    textarea: css`
      resize: none !important;
      border: none !important;
      box-shadow: none !important;
      background: transparent !important;
      padding: 0 2px;
      border-radius: 0px;
      font-size: 14px;
      line-height: 22px !important;

      &:focus,
      &:hover {
        box-shadow: none !important;
        border-color: transparent !important;
      }
    `,
    hiddenFileInput: css`
      display: none;
    `,
    attachmentList: css`
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 8px;
    `,
    attachmentItem: css`
      display: inline-flex;
      align-items: center;
      gap: 6px;
      max-width: 240px;
      padding: 4px 10px;
      border-radius: 999px;
      background: ${token.colorFillTertiary};
      color: ${token.colorText};
      font-size: 12px;
      line-height: 18px;
    `,
    attachmentName: css`
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    attachmentRemoveButton: css`
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 18px;
      height: 18px;
      padding: 0;
      border: none;
      background: transparent;
      color: ${token.colorTextSecondary};
      cursor: pointer;

      &:hover {
        color: ${token.colorText};
      }
    `,
    selectedKnowledgeList: css`
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      margin-bottom: 7px;
    `,
    selectedKnowledgeItem: css`
      display: inline-flex;
      align-items: center;
      gap: 5px;
      max-width: 240px;
      min-height: 26px;
      padding: 3px 8px;
      border: 1px solid transparent;
      border-radius: 5px;
      font-size: 12px;
      line-height: 18px;
      cursor: pointer;

      > span:first-of-type {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    `,
    selectedKnowledgeRemoveIcon: css`
      flex-shrink: 0;
      font-size: 10px;
      opacity: 0.72;
    `,
    knowledgeTermMention: css`
      color: ${token.colorInfoText};
      border-color: ${token.colorInfoBorder};
      background: ${token.colorInfoBg};
    `,
    businessLogicMention: css`
      color: ${token.colorSuccessText};
      border-color: ${token.colorSuccessBorder};
      background: ${token.colorSuccessBg};
    `,
    sqlTemplateMention: css`
      color: ${token.colorWarningText};
      border-color: ${token.colorWarningBorder};
      background: ${token.colorWarningBg};
    `,
    bottomAddonsRow: css`
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-top: 4px;
    `,
    bottomAddonsLeft: css`
      min-width: 0;
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: flex-start;
    `,
    bottomAddonsRight: css`
      display: flex;
      align-items: center;
      gap: 6px;
      justify-content: flex-end;
      flex-shrink: 0;
    `,
    attachmentButton: css`
      border-radius: 50% !important;
      color: ${token.colorTextSecondary};

      &:hover {
        color: ${token.colorPrimary};
      }
    `,
    sendButton: css`
      border-radius: 50% !important;
      background: transparent !important;
      color: ${token.colorPrimary} !important;
      box-shadow: none !important;
      transition: opacity 0.2s, color 0.2s;

      &:hover {
        opacity: 0.85;
        color: ${token.colorPrimaryHover} !important;
      }

      &[disabled] {
        background: transparent !important;
        color: ${token.colorTextDisabled} !important;
        box-shadow: none !important;
        opacity: 1;
      }
    `,
    stopButton: css`
      border-radius: 50% !important;
      color: ${token.colorError};
    `,
  };
});
