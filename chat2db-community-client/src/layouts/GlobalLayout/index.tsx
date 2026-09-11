import { FC, useEffect } from 'react';
import { Outlet } from 'umi';
import { Spin } from 'antd';
import { createStyles } from 'antd-style';
import AppTheme, { AppThemeProps } from '@/components/AppTheme';
import LoadingGracile from '@/components/Loading/LoadingGracile';
import ConfigProvider from '@/components/ConfigProvider';
import GlobalStyle from '@/styles/global';
import clientExtension from '@client-extension';
import GlobalComponent from '@/layouts/init/GlobalComponent';
import useInit from '../init/init';
import { useGlobalStore } from '@/store/global';
import { ServiceStatus } from '@/constants/common';
import AppTitleBar from './AppTitleBar';
import { insertOpenScreenAnimationExpand } from '@/utils/dom';
import i18n from '@/i18n';
import '@/assets/fonts/new-chat2db-iconfont';
import '@/assets/fonts/new-chat2db-colourful-iconfont';
import '@/assets/fonts/oscar-iconfont';
import { isDesktop } from '@/utils/env';

interface GlobalLayoutProps extends AppThemeProps {}

const useStyles = createStyles(({ css }) => {
  return {
    app: css`
      height: 100%;
      display: flex;
      flex-direction: column;
    `,
    appContent: css`
      flex: 1;
      height: 0px;
      position: relative;
    `,
    loadingBox: css`
      height: 100%;
      display: flex;
      justify-content: center;
      align-items: center;
    `,
    extend: css`
      display: flex;
      align-items: center;
      height: 40px;
      padding-left: 10px;
      span {
        margin-left: 10px;
      }
    `,
  };
});

const GlobalLayout: FC<GlobalLayoutProps> = () => {
  const { styles } = useStyles();
  const serviceStatus = useGlobalStore((s) => s.serviceStatus);

  useInit();

  useEffect(() => {
    if (isDesktop) {
      insertOpenScreenAnimationExpand(
        <div className={styles.extend}>
          <LoadingGracile />
          <span>{i18n('common.text.startingService')}</span>
        </div>,
      );
    }
  }, []);

  const renderApp = () => {
    // Waiting status page
    if (serviceStatus === ServiceStatus.PENDING && isDesktop) {
      return (
        <div className={styles.loadingBox}>
          <Spin size="large" />
        </div>
      );
    }
    return <Outlet />;
  };

  return (
    <ConfigProvider>
      <AppTheme>
        <GlobalStyle />
        {clientExtension.globalComponents}
        <GlobalComponent />
        <div className={styles.app}>
          <AppTitleBar />
          <div className={styles.appContent}>{renderApp()}</div>
        </div>
      </AppTheme>
    </ConfigProvider>
  );
};

export default GlobalLayout;
