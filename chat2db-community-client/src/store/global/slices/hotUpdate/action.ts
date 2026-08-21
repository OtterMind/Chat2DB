import { clientRuntime } from '@client-runtime';
import {
  COMMUNITY_GITHUB_RELEASES_URL,
  getCommunityGitHubReleaseTagUrl,
  UpdatedStatus,
} from '@/constants/settings';
import jcefApi from '@/jcef';
import { IHotUpdateConfig, IUpdateDetail, UpdateFailureReason, UpdateFailureStage } from '@/typings/settings';
import { isDesktop, isDevelopment } from '@/utils/env';
import { Platform } from '@/constants/os';
import produce from 'immer';
import type { StateCreator } from 'zustand/vanilla';
import { GlobalStore } from '../../store';
import { createCheckUpdateCoordinator } from './checkCoordinator';
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
  // Apply the native exit/elevated-helper result to the update state machine.
  handleApplicationExitResult: (result: ApplicationExitResult) => void;
}

export type ApplicationExitResult = {
  reason?: string;
  result?: 'ACCEPTED' | 'CANCELLED' | 'FAILED';
};

export type ManualRecoveryAction = {
  url: string;
  version?: string;
};

export const isWindowsDesktopUpdatePlatform = (osType = window.navigator.os_type): boolean =>
  osType === Platform.Windows;

/** Return the safe, user-initiated Release page for an update that is available. */
export const getManualDownloadAction = (detail: IUpdateDetail): ManualRecoveryAction | null => {
  if (detail.status !== UpdatedStatus.Available || !detail.version || !/^[0-9]+(?:\.[0-9]+)+$/.test(detail.version)) {
    return null;
  }
  const fallbackUrl = getCommunityGitHubReleaseTagUrl(detail.version);
  return {
    url: detail.releasePageUrl === fallbackUrl ? detail.releasePageUrl : fallbackUrl,
    version: detail.version,
  };
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
        if (!clientRuntime.enableAutoUpdate || !isWindowsDesktopUpdatePlatform()) {
          return;
        }
        if (isDesktop && isDevelopment) {
          return;
        }
        const detail = get().updateDetail;
        const shouldInstall = detail.status === UpdatedStatus.Updated
          || (detail.status === UpdatedStatus.UpdateFailed && detail.failureStage === 'INSTALL');
        if (shouldInstall) {
          get().setUpdateDetail({
            status: UpdatedStatus.Installing,
          });
          try {
            const installed = await jcefApi.triggerInstallation();
            if (!installed) {
              get().setUpdateDetail({
                status: UpdatedStatus.UpdateFailed,
                failureStage: 'INSTALL',
                failureReason: 'UNKNOWN',
              });
              return;
            }
            // On Windows the bridge has only accepted an exit-confirmation request.
            // The helper is launched after that confirmation, so neither report an
            // installed update nor send the generic restart command from here.
            return;
          } catch {
            get().setUpdateDetail({
              status: UpdatedStatus.UpdateFailed,
              failureStage: 'INSTALL',
              failureReason: 'UNKNOWN',
            });
            return;
          }
        }
        if (get().updateDetail.status !== UpdatedStatus.Installed) {
          return;
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
    handleApplicationExitResult: ({ reason, result }) => {
      if (reason !== 'INSTALL_UPDATE') {
        return;
      }
      if (result === 'CANCELLED') {
        get().setUpdateDetail({
          status: UpdatedStatus.Updated,
          failureStage: undefined,
          failureReason: undefined,
        });
      } else if (result === 'FAILED') {
        get().setUpdateDetail({
          status: UpdatedStatus.UpdateFailed,
          failureStage: 'INSTALL',
          failureReason: 'UNKNOWN',
        });
      }
    },
    handleCheckUpdate: () => {
      if (!isDesktop || !clientRuntime.enableAutoUpdate) {
        return Promise.resolve(false);
      }
      return runCheckUpdate();
    },
    downloadUpdate: () => {
      if (!isDesktop || !clientRuntime.enableAutoUpdate || !isWindowsDesktopUpdatePlatform()
        || (isDesktop && isDevelopment)) {
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
      if (!isDesktop || !clientRuntime.enableAutoUpdate) {
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
      if (property === 'receiveBeta' && isDesktop && clientRuntime.enableAutoUpdate) {
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
