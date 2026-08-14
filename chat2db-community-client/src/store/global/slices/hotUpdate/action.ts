import { runtimeEditionConfig } from '@/constants/runtimeEdition';
import { UpdatedStatus } from './status';
import jcefApi from '@/jcef';
import { IHotUpdateConfig, IUpdateDetail, UpdateFailureReason, UpdateFailureStage } from '@/typings/settings';
import { isDesktop, isDevelopment } from '@/utils/env';
import produce from 'immer';
import type { StateCreator } from 'zustand/vanilla';
import { GlobalStore } from '../../store';
import { createCheckUpdateCoordinator } from './checkCoordinator';
import {
  COMMUNITY_GITHUB_RELEASES_URL,
  getCommunityGitHubReleaseTagUrl,
} from '@/constants/settings';

export { COMMUNITY_GITHUB_RELEASES_URL, getCommunityGitHubReleaseTagUrl };

export interface HotUpdateAction {
  // Update and restart the app
  updateAndRestartApp: () => Promise<void>;
  // Check for updates
  handleCheckUpdate: () => Promise<boolean>;
  // Download a checked update
  downloadUpdate: () => Promise<boolean>;
  // Synchronize updater-owned preferences
  syncUpdatePreferences: () => Promise<void>;
  // Update hot update configuration
  updateHotUpdateConfig: (property: keyof IHotUpdateConfig, value: any) => Promise<void>;
}

export type ManualRecoveryAction = {
  url: string;
  version?: string;
};

/**
 * Derive the user-clicked manual recovery action for an update failure.
 * Returns null when there is no failure or when no recovery URL can be built.
 * This helper never opens a browser; callers must invoke openWebPage themselves.
 */
export const getManualRecoveryAction = (detail: IUpdateDetail): ManualRecoveryAction | null => {
  if (detail.status !== UpdatedStatus.UpdateFailed) {
    return null;
  }
  if (!detail.version) {
    return { url: COMMUNITY_GITHUB_RELEASES_URL };
  }
  return {
    url: detail.releasePageUrl || getCommunityGitHubReleaseTagUrl(detail.version),
    version: detail.version,
  };
};

const buildDownloadFailureDetail = (currentDetail: IUpdateDetail): IUpdateDetail => ({
  status: UpdatedStatus.UpdateFailed,
  failureStage: 'DOWNLOAD' as UpdateFailureStage,
  failureReason: currentDetail.failureReason || ('UNKNOWN' as UpdateFailureReason),
  releasePageUrl: currentDetail.releasePageUrl,
  version: currentDetail.version,
});

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
            get().setUpdateDetail(buildDownloadFailureDetail(get().updateDetail));
            return false;
          }
          return true;
        })
        .catch(() => {
          get().setUpdateDetail(buildDownloadFailureDetail(get().updateDetail));
          return false;
        })
        .finally(() => {
          activeDownload = undefined;
        });
      return activeDownload;
    },
    syncUpdatePreferences: async () => {
      if (!isDesktop || !runtimeEditionConfig.autoUpdate) {
        return;
      }
      try {
        const preferences = await jcefApi.updatePreferences();
        set({
          hotUpdateConfig: produce(get().hotUpdateConfig, (draft) => {
            draft.receiveBeta = preferences.receiveBeta;
          }),
        });
      } catch {
        // Keep the last locally confirmed preference when the desktop bridge fails.
      }
    },
    updateHotUpdateConfig: async (property, value) => {
      let persistedValue = value;
      if (property === 'receiveBeta' && isDesktop && runtimeEditionConfig.autoUpdate) {
        try {
          const preferences = await jcefApi.updatePreferences({ receiveBeta: Boolean(value) });
          if (!preferences.saved) {
            return;
          }
          persistedValue = preferences.receiveBeta;
        } catch {
          return;
        }
      }
      set({
        hotUpdateConfig: produce(get().hotUpdateConfig, (draft) => {
          draft[property] = persistedValue;
        }),
      });
    },
  };
};
