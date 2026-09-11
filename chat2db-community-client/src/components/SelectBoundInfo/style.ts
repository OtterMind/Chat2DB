import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    selectBoundInfo: css`
      display: flex;
      align-items: center;
      gap: 8px;
      height: 100%;
    `,
    boundInfoBox: css`
      max-width: 160px;
      display: flex;
      align-items: center;
      padding: 4px 6px 4px 8px;
      border-radius: 4px;
      cursor: pointer;
      &:hover {
        background-color: ${token.colorFillSecondary};
      }
    `,
    boundInfoName: css`
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
      margin: 0px 3px 0px 4px;
      font-size: 13px;
    `,
    suffixIcon: css`
      transform: rotate(90deg);
    `,
    toolbarBtn: css`
      padding-top: 2px;
      padding-bottom: 2px;
      transition: all 0.2s;
      &:hover {
        background-color: ${token.colorFillSecondary};
      }
    `,
    dropdownTrigger: css`
      display: inline-flex;
      min-width: 0;
    `,
    noPermission: css`
      color: ${token.colorTextSecondary};
      margin: 0px 4px;
    `,
    environmentName: css`
      margin-left: 6px;
      color: ${token.colorTextSecondary};
      font-size: 12px;
    `,
    dropdownItemLabel: css`
      display: flex;
      align-items: center;
      gap: 4px;
    `,
    dataSourceIdentityIcon: css`
      display: inline-flex;
      flex: 0 0 auto;
      gap: 4px;
      align-items: center;
      min-width: 27px;
    `,
    dropdownContent: css`
      border: 1px solid ${token.colorBorder};
      border-radius: 4px;
      overflow: hidden;
      .ant-input-affix-wrapper {
        border: 0px;
        border-radius: 0px;
        padding: 4px 8px;
        border-bottom: 1px solid ${token.colorBorder} !important;
      }
      .ant-dropdown-menu {
        border: 0px;
        border-radius: 0px !important;
      }
    `,
    noData: css`
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100%;
      padding: 8px;
      background-color: ${token.colorBgElevated};
    `,
  };
});
