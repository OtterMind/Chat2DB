import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { UpdatedStatus } from './status';
import jcefApi from '@/jcef';
import { IHotUpdateConfig } from '@/typings/settings';
import { isDesktop, isDevelopment } from '@/utils/env';
import produce from 'immer';
import type { StateCreator } from 'zustand/vanilla';
import { GlobalStore } from '../../store';
import { createCheckUpdateCoordinator } from './checkCoordinator';

export interface HotUpdateAction {
  // Update and restart the app
  updateAndRestartApp: () => Promise<void>;
  // Check for updates
  handleCheckUpdate: () => Promise<boolean>;
  // Download a checked update
  downloadUpdate: () => Promise<boolean>;
  // Update hot update configuration
  updateHotUpdateConfig: (property: keyof IHotUpdateConfig, value: any) => void;
}

export const createHotUpdateAction: StateCreator<GlobalStore, [['zustand/devtools', never]], [], HotUpdateAction> = (
  set,
  get,
) => {
  let activeDownload: Promise<boolean> | undefined;
  let activeRestart: Promise<void> | undefined;
  const runCheckUpdate = createCheckUpdateCoordinator(jcefApi.appCheckUpdate, (detail) =>
    get().setUpdateDetail(detail),
  );

  return {
    updateAndRestartApp: async () => {
      if (activeRestart) {
        return activeRestart;
      }
      activeRestart = (async () => {
        if (!runtimeEditionConfig.autoUpdate) {
          return;
        }
        if (isDesktop && isDevelopment) {
          return;
        }
        if (get().updateDetail.status === UpdatedStatus.Updated) {
          get().setUpdateDetail({
            status: UpdatedStatus.Installing,
          });
          try {
            const installed = await jcefApi.triggerInstallation();
            if (!installed) {
              get().setUpdateDetail({
                status: UpdatedStatus.UpdateFailed,
              });
              return;
            }
            get().setUpdateDetail({
              status: UpdatedStatus.Installed,
            });
          } catch {
            get().setUpdateDetail({
              status: UpdatedStatus.UpdateFailed,
            });
            return;
          }
        }
        try {
          await jcefApi.restartApp();
        } catch {
          get().setUpdateDetail({
            status: UpdatedStatus.UpdateFailed,
          });
        }
      })().finally(() => {
        activeRestart = undefined;
      });
      return activeRestart;
    },
    handleCheckUpdate: () => {
      if (!isDesktop || !runtimeEditionConfig.autoUpdate) {
        return Promise.resolve(false);
      }
      return runCheckUpdate();
    },
    downloadUpdate: () => {
      if (!isDesktop || !runtimeEditionConfig.autoUpdate || (isDesktop && isDevelopment)) {
        return Promise.resolve(false);
      }
      if (activeDownload) {
        return activeDownload;
      }
      if (get().updateDetail.status !== UpdatedStatus.Available) {
        return Promise.resolve(false);
      }
      get().setUpdateDetail({ status: UpdatedStatus.Updating, progress: 0 });
      activeDownload = jcefApi
        .triggerDownload()
        .then((downloaded) => {
          if (!downloaded) {
            get().setUpdateDetail({ status: UpdatedStatus.UpdateFailed });
            return false;
          }
          return true;
        })
        .catch(() => {
          get().setUpdateDetail({ status: UpdatedStatus.UpdateFailed });
          return false;
        })
        .finally(() => {
          activeDownload = undefined;
        });
      return activeDownload;
    },
    updateHotUpdateConfig: (property, value) => {
      set({
        hotUpdateConfig: produce(get().hotUpdateConfig, (draft) => {
          draft[property] = value;
        }),
      });
    },
  };
};
