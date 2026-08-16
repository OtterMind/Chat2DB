import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  menu: css`
    padding: 6px;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 8px;
    background: ${token.colorBgElevated};
    box-shadow: ${token.boxShadowSecondary};

    .ant-menu-item,
    .ant-menu-submenu-title {
      display: flex;
      align-items: center;
      justify-content: flex-start;
      height: 30px;
      margin: 0;
      padding: 0 10px;
      border-radius: 5px;
      color: ${token.colorText};
      font-size: 13px;
      line-height: 30px;
      text-align: left;
    }

    .ant-menu-title-content {
      flex: 1 1 auto;
      min-width: 0;
      text-align: left;
    }

    .ant-menu-item-icon {
      width: 16px;
      min-width: 16px;
      height: 16px;
    }

    .ant-menu-item:hover,
    .ant-menu-submenu-title:hover {
      background: ${token.colorFillTertiary};
    }

    .ant-menu-item-danger {
      color: ${token.colorError};
    }

    .ant-menu-item-disabled {
      color: ${token.colorTextQuaternary} !important;
    }

    .ant-menu-item-divider {
      margin: 6px 8px;
      background: ${token.colorSplit};
    }
  `,
  defaultMenuWidth: css`
    min-width: 160px;
  `,
  menuIconSlot: css`
    display: inline-flex;
    flex: 0 0 16px;
    align-items: center;
    justify-content: center;
    width: 16px;
    min-width: 16px;
    height: 16px;

    > svg,
    > i {
      width: 16px !important;
      height: 16px !important;
      min-width: 16px;
      font-size: 16px !important;
    }
  `,
  menuLabel: css`
    display: block;
    width: 100%;
    text-align: left;
  `,
}));
