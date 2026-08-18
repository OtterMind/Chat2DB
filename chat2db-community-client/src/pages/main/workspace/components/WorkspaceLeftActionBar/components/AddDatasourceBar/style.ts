import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    addDatasourceButton: css`
      .chat2db-iconfont {
        transform: translateX(-2px);
      }
    `,
    datasourceOverlay: css`
      background-color: ${token.colorBgBase};
      border-radius: ${token.borderRadius}px;
      .ant-dropdown-menu-item .ant-dropdown-menu-item-icon {
        font-size: 20px !important;
      }
    `,
    datasourceSearchItem: css`
      width: 180px;
      padding: 2px 0;
      cursor: default;

      .ant-input-affix-wrapper {
        width: 100%;
      }
    `,
    datasourceSearchEmpty: css`
      width: 180px;
      color: ${token.colorTextTertiary};
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
