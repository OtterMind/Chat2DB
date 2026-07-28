import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }, { inputNumberWidth }: { inputNumberWidth: number }) => {
  return {
    paginationWrapper: css`
      display: flex;
      justify-content: start;
      align-items: center;
      height: 30px;
    `,
    itemIcon: css`
      height: 24px;
      width: 24px;
      display: flex;
      justify-content: center;
      align-items: center;
      font-size: 10px;
      background-color: transparent;
      border-radius: 4px;
      outline: none;
      transition: border 0.2s;
      color: var(--color-text);
      cursor: pointer;
      user-select: none;
      box-sizing: border-box;
      &:hover {
        background-color: ${token.colorBgTextHover};
        color: ${token.colorLink};
      }
    `,
    itemIconDisabled: css`
      &,
      &:hover {
        cursor: not-allowed;
        color: ${token.colorTextDisabled};
      }
    `,
    inputNumber: css`
      width: ${inputNumberWidth}px;
      background-color: transparent !important;
      border: none !important;
      box-shadow: none !important;
      .ant-input-number-outlined:focus {
        box-shadow: none;
        background-color: transparent;
      }
      .ant-input-number-outlined:focus-within {
        box-shadow: none;
        background-color: transparent;
      }
      .ant-input-number-input {
        text-align: center;
        padding: 0px;
      }
      .ant-input-number-input {
        font-size: 13px;
      }
      .ant-input-number-input {
        padding: 1.5px 4px !important;
      }
    `,
    selectSize: css`
      flex-shrink: 0;
      font-size: 13px;
      color: ${token.colorText};
      cursor: pointer;
      margin-left: 10px;
      display: flex;
      align-items: center;
      gap: 4px;
      &:hover {
        color: ${token.colorPrimaryText};
      }
    `,
    pageSizeOption: css`
      min-width: 150px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    `,
    defaultPageSize: css`
      display: inline-flex;
      align-items: center;
      gap: 4px;
      color: ${token.colorTextSecondary};
      font-size: 12px;
      flex-shrink: 0;
      white-space: nowrap;
    `,
    customPageSize: css`
      min-width: 150px;
      display: flex;
      align-items: center;
      gap: 12px;
    `,
    customPageSizeInput: css`
      width: 90px;
      flex: 1;
      min-width: 0;
    `,
    totalButton: css`
      flex-shrink: 0;
      font-size: 13px;
      margin: 0px 8px;
      height: 24px;
      line-height: 24px;
      padding: 0px 6px;
      gap: 5px;
      /* color: ${token.colorText};
      border: none;
      &:hover {
        color: ${token.colorLinkHover};
      } */
    `,
    totalContainer: css`
      font-size: 13px;
      padding: 0px;
      margin: 0px 10px;
      height: 30px;
      line-height: 30px;
      color: ${token.colorText};
    `,
  };
});
