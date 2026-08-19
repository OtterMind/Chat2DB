import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    trigger: css`
      color: ${token.colorTextTertiary};

      &:hover {
        color: ${token.colorTextSecondary};
      }
    `,
    datasourceOverlay: css`
      background-color: ${token.colorBgBase};
      border-radius: ${token.borderRadius}px;
      .ant-dropdown-menu-item {
        padding: 5px 6px !important;
      }
      .ant-dropdown-menu-submenu-title {
        padding: 5px 6px !important;
      }
    `,
    iconPlaceholder: css`
      width: 20px;
    `,
    labelTitleBox: css`
      display: flex;
      align-items: center;
    `,
    labelSelect: css`
      color: ${token.colorPrimary};
    `,
    labelTitle: css`
      line-height: 20px;
      margin-left: 2px;
    `,
    title: css`
      display: flex;
      align-items: center;
      font-size: 18px;
      text-align: center;
    `,
    titleIcon: css`
      margin-right: 8px;
    `,
  };
});
