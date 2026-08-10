import { useEffect, useRef } from 'react';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { JavaPushActionType, JcefEventBus } from '@/jcef/eventBus';
import importExportServices from '@/service/importExport';
import { useGlobalStore } from '@/store/global';
import { isDesktop } from '@/utils/env';
import { coordinateApplicationExit } from './applicationExitCoordinator';

const useApplicationExit = () => {
  const handlingExitRef = useRef(false);

  useEffect(() => {
    if (!isDesktop) return;

    const handleExitRequested = async () => {
      if (handlingExitRef.current) return;
      handlingExitRef.current = true;
      try {
        await coordinateApplicationExit({
          getActiveTaskCount: () => importExportServices.getActiveTaskCount(undefined),
          prepareUserExit: () => importExportServices.prepareUserExit(undefined),
          abortUserExit: () => importExportServices.abortUserExit(undefined),
          confirmCloseWindow: jcefApi.confirmCloseWindow,
          onCancel: () => {
            handlingExitRef.current = false;
          },
          requestConfirmation: ({ activeTaskCount, onCancel, onConfirm }) => {
            useGlobalStore.getState().openUnifiedConfirmationModal({
              title: i18n('workspace.title.exitApplication'),
              content: i18n('workspace.text.exitWithActiveTasks', activeTaskCount),
              headerIconCode: 'icon-exclamation-circle',
              onCancel,
              onOk: onConfirm,
            });
          },
        });
      } catch {
        handlingExitRef.current = false;
      }
    };

    JcefEventBus.on(JavaPushActionType.APP_EXIT_REQUESTED, handleExitRequested);
    return () => {
      JcefEventBus.off(JavaPushActionType.APP_EXIT_REQUESTED, handleExitRequested);
    };
  }, []);
};

export default useApplicationExit;
