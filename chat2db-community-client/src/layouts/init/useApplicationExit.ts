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
  const activeExitOperationIdRef = useRef<string>();

  useEffect(() => {
    if (!isDesktop) return;

    const handleExitRequested = async (request?: { operationId?: string; reason?: string }) => {
      const operationId = request?.operationId;
      if (!operationId || handlingExitRef.current) return;
      handlingExitRef.current = true;
      activeExitOperationIdRef.current = operationId;
      const cancelNativeExit = () => jcefApi.cancelApplicationExit({ operationId });
      const applyExitResult = (result: 'CANCELLED' | 'FAILED') => {
        useGlobalStore.getState().handleApplicationExitResult({ reason: request?.reason, result });
      };
      try {
        await coordinateApplicationExit({
          getActiveTaskCount: () => importExportServices.getActiveTaskCount(undefined),
          prepareUserExit: () => importExportServices.prepareUserExit(undefined),
          abortUserExit: () => importExportServices.abortUserExit(undefined),
          confirmCloseWindow: () => jcefApi.confirmCloseWindow({ operationId }),
          cancelApplicationExit: cancelNativeExit,
          onCancel: () => {
            void cancelNativeExit()
              .then((cancelled) => {
                if (cancelled) applyExitResult('CANCELLED');
              })
              .finally(() => {
                handlingExitRef.current = false;
                activeExitOperationIdRef.current = undefined;
              });
          },
          requestConfirmation: ({ activeTaskCount, onCancel, onConfirm }) => {
            useGlobalStore.getState().openUnifiedConfirmationModal({
              title: i18n('workspace.title.exitApplication'),
              content: i18n('workspace.text.exitWithActiveTasks', activeTaskCount),
              headerIconCode: 'icon-exclamation-circle',
              onCancel,
              onOk: async () => {
                try {
                  await onConfirm();
                } catch (error) {
                  applyExitResult('FAILED');
                  throw error;
                } finally {
                  handlingExitRef.current = false;
                  activeExitOperationIdRef.current = undefined;
                }
              },
            });
          },
        });
      } catch {
        await cancelNativeExit().catch(() => undefined);
        applyExitResult('FAILED');
        handlingExitRef.current = false;
        activeExitOperationIdRef.current = undefined;
      }
    };

    const handleExitResult = (result?: {
      operationId?: string;
      reason?: string;
      result?: 'ACCEPTED' | 'CANCELLED' | 'FAILED';
    }) => {
      if (!result?.operationId || result.operationId !== activeExitOperationIdRef.current) {
        return;
      }
      useGlobalStore.getState().handleApplicationExitResult(result || {});
      handlingExitRef.current = false;
      activeExitOperationIdRef.current = undefined;
    };

    JcefEventBus.on(JavaPushActionType.APP_EXIT_REQUESTED, handleExitRequested);
    JcefEventBus.on(JavaPushActionType.APP_EXIT_RESULT, handleExitResult);
    return () => {
      JcefEventBus.off(JavaPushActionType.APP_EXIT_REQUESTED, handleExitRequested);
      JcefEventBus.off(JavaPushActionType.APP_EXIT_RESULT, handleExitResult);
    };
  }, []);
};

export default useApplicationExit;
