import { initializeMonacoEditor } from '@/components/SQLEditor';
import { ServiceStatus } from '@/constants/common';
import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import useDocumentListener from '@/hooks/useDocumentListener';
import useCopyFocusData from '@/hooks/useFocusData';
import useJavaMessageReceiver from '@/jcef/useProcessJavaPush';
import miscServices from '@/service/misc';
import supportedDatabaseService from '@/service/supportedDatabase';
import { useGlobalStore } from '@/store/global';
import { clearOlderLocalStorage } from '@/utils';
import { buildIconSprite, registerDynamicDatabases } from '@/utils/dynamicDatabaseRegistry';
import { databaseMap, databaseTypeList } from '@/constants/database';
import {
  dataSourceFormConfigs,
  envItem,
  portItem,
  sshConfig,
  storageItem,
} from '@/components/ConnectionEdit/config/dataSource';
import { isDesktop } from '@/utils/env';
import { initGoogleAds } from '@/utils/googleAds';
import { initializeDevEnvironmentIcon } from '@/utils/initLocalIcon';
import queryString from 'query-string';
import { useEffect, useLayoutEffect } from 'react';
import { modifiedGlobalVariable } from './modifiedGlobalVariable';
import registerMessage from './registerMessage';
import registerNotification from './registerNotification';
import useDesktopInputFocusFix from './useDesktopInputFocusFix';
import useEnglish from './useEnglish';
import useIframe from './useIframe';
import useJcef from './useJcef';
import useOpenFile from './useOpenFile';

const useInit = () => {
  const { reload } = queryString.parse(location.search);
  const { queryAppConfig, serviceStatus, setServiceStatus } = useGlobalStore((state) => ({
    queryAppConfig: state.queryAppConfig,
    serviceStatus: state.serviceStatus,
    setServiceStatus: state.setServiceStatus,
  }));
  const { curCountry, isCN } = useGlobalStore((state) => ({
    curCountry: state.appConfig.curCountry,
    isCN: state.appConfig.isCN,
  }));

  // Initialize Google Ads after the country is known.
  // This only applies to the overseas web app, and initGoogleAds is idempotent.
  useEffect(() => {
    if (isDesktop || !runtimeEditionConfig.googleAds) {
      return;
    }
    initGoogleAds();
  }, [curCountry, isCN]);

  useLayoutEffect(() => {
    modifiedGlobalVariable();
    // Initialize the icon of the development environment
    initializeDevEnvironmentIcon();
  }, []);

  // Handle global document events
  useEffect(() => {
    //Block the global default cmd+f
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'KeyF' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  useJcef();
  useDesktopInputFocusFix();
  useIframe();
  useEnglish();
  useCopyFocusData();
  useDocumentListener();
  useOpenFile();
  useJavaMessageReceiver();

  // Check service status
  const checkServiceStatus = () => {
    miscServices.testService(null).then(() => {
      setServiceStatus(ServiceStatus.SUCCESS);
    });
  };

  useEffect(() => {
    if (isDesktop) {
      checkServiceStatus();
    }
  }, [reload, isDesktop]);

  useEffect(() => {
    if (serviceStatus === ServiceStatus.PENDING && isDesktop) {
      return;
    }
    queryAppConfig();
    clearOlderLocalStorage();
    registerMessage();
    registerNotification();
    initializeMonacoEditor();
    // Surface backend configuration-only databases without a client rebuild.
    supportedDatabaseService
      .listSupported({})
      .then((summaries) => {
        const added = registerDynamicDatabases(
          summaries,
          { databaseMap, databaseTypeList, dataSourceFormConfigs },
          { envItem, storageItem, portItem, sshConfig },
        );
        if (added.length) {
          const sprite = buildIconSprite((summaries || []).filter((s) => added.includes(s.dbType)));
          if (sprite && !document.getElementById('c2d-dynamic-db-icons')) {
            const host = document.createElement('div');
            host.id = 'c2d-dynamic-db-icons';
            host.innerHTML = sprite;
            document.body.appendChild(host);
          }
        }
      })
      .catch(() => {
        // Older backends without the endpoint keep the built-in list.
      });
  }, [serviceStatus, reload, isDesktop]);
};

export default useInit;
