import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, cx, token }) => {
  const deleteIconBox = cx(css`
    display: none;
    flex-shrink: 0;
  `);
  return {
    modalContent: css`
      padding-top: 20px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    `,
    tips: css`
      font-size: 12px;
      color: ${token.colorTextSecondary};
    `,
    masterPasswordInput: css`
      margin-top: 4px;
    `,
    uploadDragger: css`
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 63px 0px;
      p {
        font-size: 14px;
      }
    `,
    uploadDraggerIcon: css`
      color: ${token.colorPrimary};
    `,
    hiddenUploadDraggerBox: css`
      height: 0px;
      overflow: hidden;
      opacity: 0;
    `,
    uploadLocalFile: css`
      height: 226px;
      border: 1px solid ${token.colorBorder};
      border-radius: 8px;
      font-size: 12px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      box-sizing: border-box;
    `,
    uploadLocalFileHeader: css`
      width: 100%;
      flex-shrink: 0;
      display: flex;
      height: 26px;
      font-size: 13px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0px 4px 0px 10px;
      box-sizing: border-box;
      background-color: ${token.colorFillQuaternary};
      border-bottom: 1px solid ${token.colorBorderSecondary};
      .ant-upload-wrapper{
        height: 20px;
      }
    `,
    uploadLocalFileBody: css`
      height: calc(100% - 26px - 4px);
      margin-top: 4px;
      width: 100%;
      box-sizing: border-box;
      display: flex;
      flex-direction: column;
      align-items: center;
      overflow-y: auto;
      overflow-x: hidden;
      padding: 0px 12px 8px 12px;
    `,
    deleteIconBox,
    deleteIcon: css`
      &:hover {
        background: ${token.colorPrimaryBgHover};
        color: ${token.colorPrimary};
      }
    `,
    fileItem: css`
      width: 100%;
      line-height: 24px;
      padding: 0px 3px 0px 6px;
      border-radius: 4px;
      cursor: pointer;
      display: flex;
      align-items: center;
      span {
        flex: 1;
      }
      &:hover {
        background-color: ${token.colorPrimaryBgHover};
        .${deleteIconBox} {
          display: block;
        }
      }
    `,
    addIcon: css`
      color: ${token.colorText};
      &:hover {
        color: ${token.colorPrimary};
      }
    `,
    description: css`
      display: flex;
      flex-direction: column;
      align-items: center;
    `,
    description1: css`
      font-size: 14px;
    `,
    description2: css`
      font-size: 12px;
      color: ${token.colorTextTertiary};
    `,
  };
});
