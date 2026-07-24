import { createStyles } from 'antd-style';
import {hexToRgba} from '@/utils/color';

export const useStyles = createStyles(({ css, token }) => {

  const tableLoadingBg = hexToRgba(token.colorFill, 20);

  return {
    container: css`
      height: 100%;
      display: flex;
      flex-direction: column;
      position: relative;
      &:focus-visible {
        outline: none;
      }
    `,
    resultSetContent: css`
      flex: 1;
      height: 0;
      display: flex;
      min-width: 0;
      min-height: 0;
      position: relative;
    `,
    resultSetTableContainer: css`
      flex: 1;
      min-width: 0;
      min-height: 0;
      position: relative;
    `,
    inspector: css`
      width: 100%;
      height: 100%;
      min-width: 0;
      min-height: 0;
      overflow: hidden;
      background: ${token.colorBgContainer};
      border-left: 1px solid ${token.colorBorderSecondary};
    `,
    inspectorTabs: css`
      height: 100%;

      .ant-tabs-nav {
        flex-shrink: 0;
        margin: 0;
        padding-left: 10px;
      }

      .ant-tabs-content-holder,
      .ant-tabs-content,
      .ant-tabs-tabpane {
        height: 100%;
        min-height: 0;
      }

      .ant-tabs-content-holder {
        overflow: hidden;
      }
    `,
    inspectorClose: css`
      width: 28px;
      height: 28px;
      padding: 0;
      margin-right: 4px;
      color: ${token.colorTextSecondary};
    `,
    tableLoading: css`
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      display: flex;
      flex-direction: column;
      justify-content: center;
      align-items: center;
      overflow: hidden;
      z-index: 1;
      background-color: ${tableLoadingBg};
    `,
    stopExecuteSql: css`
      cursor: pointer;
      margin-top: 30px;
      &:hover {
        color: ${token.colorPrimary};
      }
    `,
  };
});
