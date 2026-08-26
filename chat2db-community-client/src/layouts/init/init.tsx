import { initializeMonacoEditor } from '@/components/SQLEditor';
import { ServiceStatus } from '@/constants/common';
import useDocumentListener from '@/hooks/useDocumentListener';
import useCopyFocusData from '@/hooks/useFocusData';
import useJavaMessageReceiver from '@/jcef/useProcessJavaPush';
import miscServices from '@/service/misc';
import { useGlobalStore } from '@/store/global';
import { clearOlderLocalStorage } from '@/utils';
import { isDesktop } from '@/utils/env';
import clientRuntime from '@client-runtime';
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
import useApplicationExit from './useApplicationExit';
import useTaskCenter from './useTaskCenter';
import { shouldAutoPollTaskCenter } from './taskCenterPolling';

const useInit = () => {
  const { reload } = queryString.parse(location.search);
  const { queryAppConfig, serviceStatus, setServiceStatus } = useGlobalStore((state) => ({
    queryAppConfig: state.queryAppConfig,
    serviceStatus: state.serviceStatus,
    setServiceStatus: state.setServiceStatus,
  }));
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
  useApplicationExit();
  useTaskCenter(
    shouldAutoPollTaskCenter({
      enabled: clientRuntime.enableTaskCenterAutoPolling,
      desktop: isDesktop,
      serviceReady: serviceStatus === ServiceStatus.SUCCESS,
    }),
  );

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
  }, [serviceStatus, reload, isDesktop]);
};

export default useInit;
