import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, cx, token }) => {
  // The delete action is always visible and hit-testable; a hover-only reveal makes it
  // unreachable for pointer input that does not hover first (touch, automation).
  const deleteIconBox = cx(css`
    flex-shrink: 0;
  `);
  return {
    fillHeight: css`
      display: flex;
      height: 100%;
      min-height: 226px;
      flex-direction: column;

      .ant-upload-drag,
      .ant-upload-btn,
      .ant-upload-drag-container {
        height: 100%;
      }
    `,
    grow: css`
      flex: 1;
      min-height: 0;
    `,
    fillDragger: css`
      height: 100%;
      justify-content: center;
      padding: 24px;
      box-sizing: border-box;
    `,
    uploadDragger: css`
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 63px 0px;
      /* A filled block marks the drop target; the outline is reserved for hover and drag-over. */
      border: 1px solid transparent;
      cursor: pointer;
      border-radius: 8px;
      background: ${token.colorFillQuaternary};
      transition:
        background-color 0.16s ease,
        border-color 0.16s ease;

      &:hover {
        border-color: ${token.colorPrimaryBorder};
        background: ${token.colorFillTertiary};
      }

      /* antd marks the dragger while a file is dragged over it. */
      '.ant-upload-drag-hover &' {
        border-color: ${token.colorPrimary};
        background: ${token.colorPrimaryBg};
      }

      p {
        font-size: 14px;
      }
    `,
    /**
     * antd's `Upload.Dragger` draws its own dashed frame and padding; the filled block above is the
     * only drop-target affordance, so the frame is neutralized instead of doubled.
     */
    draggerFrame: css`
      .ant-upload-drag {
        padding: 0;
        border: none;
        background: transparent;
      }

      .ant-upload-btn {
        padding: 0 !important;
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
    limitFileSize: css`
      font-size: 12px;
      color: ${token.colorTextTertiary};
    `,
  };
});
