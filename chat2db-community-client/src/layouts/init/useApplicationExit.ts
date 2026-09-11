import { useLayoutEffect, useRef } from 'react';
import clientRuntime from '@client-runtime';
import i18n from '@/i18n';
import jcefApi from '@/jcef';
import { JavaPushActionType, JcefEventBus } from '@/jcef/eventBus';
import importExportServices from '@/service/importExport';
import { useGlobalStore } from '@/store/global';
import { useUserStore } from '@/store/session';
import { useWorkspaceStore } from '@/store/workspace';
import { isDesktop } from '@/utils/env';
import { prepareWorkspaceEditorsForApplicationExit } from '@/utils/editorCloseConfirmation';
import { coordinateApplicationExit } from './applicationExitCoordinator';

const useApplicationExit = () => {
  const handlingExitRef = useRef(false);

  useLayoutEffect(() => {
    if (!isDesktop) return;

    const handleExitRequested = async (request?: { operationId?: string }) => {
      const operationId = request?.operationId;
      if (!operationId || handlingExitRef.current) return;
      handlingExitRef.current = true;
      const acknowledged = await jcefApi.acknowledgeApplicationExit({ operationId }).catch(() => false);
      if (!acknowledged) {
        handlingExitRef.current = false;
        return;
      }
      const cancelNativeExit = () => jcefApi.cancelApplicationExit({ operationId });
      try {
        await coordinateApplicationExit({
          confirmDirtyEditors: () => {
            const workspace = useWorkspaceStore.getState();
            return prepareWorkspaceEditorsForApplicationExit(
              workspace.workspaceTabList || [],
              workspace.editorList || {},
            );
          },
          shouldManageTasks: () => {
            if (!clientRuntime.requiresAuthentication && !clientRuntime.requiresLicenseActivation) {
              return true;
            }
            const session = useUserStore.getState() as ReturnType<typeof useUserStore.getState> & {
              authVerified?: boolean;
            };
            return Boolean(session.authVerified && session.curUser?.id);
          },
          getActiveTaskCount: () => importExportServices.getActiveTaskCount(undefined),
          prepareUserExit: () => importExportServices.prepareUserExit(undefined),
          abortUserExit: () => importExportServices.abortUserExit(undefined),
          confirmCloseWindow: () => jcefApi.confirmCloseWindow({ operationId }),
          cancelApplicationExit: cancelNativeExit,
          onCancel: () => {
            void cancelNativeExit().finally(() => {
              handlingExitRef.current = false;
            });
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
        await cancelNativeExit().catch(() => undefined);
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
