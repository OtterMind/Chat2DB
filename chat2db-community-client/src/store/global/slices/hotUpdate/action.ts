import { clientRuntime } from '@client-runtime';
import { UpdatedStatus } from '@/constants/settings';
import jcefApi from '@/jcef';
import { IHotUpdateConfig, UpdateCheckTrigger } from '@/typings/settings';
import { isDesktop } from '@/utils/env';
import produce from 'immer';
import type { StateCreator } from 'zustand/vanilla';
import { GlobalStore } from '../../store';

export interface HotUpdateAction {
  // Update and restart the app
  updateAndRestartApp: () => void;
  // Check for updates
  handleCheckUpdate: (trigger: UpdateCheckTrigger) => Promise<boolean>;
  // Record whether the product was activated offline
  setOfflineActivation: (value: boolean) => void;
  // Synchronize updater-owned preferences
  syncUpdatePreferences: () => Promise<void>;
  // Update hot update configuration
  updateHotUpdateConfig: (property: keyof IHotUpdateConfig, value: any) => Promise<void>;
}

// The desktop updater runs one check at a time, so overlapping triggers (a click while a scheduled
// check runs, or several clicks in a row) share the running check instead of queueing more.
let pendingCheck: Promise<boolean> | null = null;

/** Whether the stored state still has an update for the user to download or install. */
const hasPendingUpdate = (status: UpdatedStatus) =>
  status === UpdatedStatus.Available ||
  status === UpdatedStatus.Updating ||
  status === UpdatedStatus.Updated ||
  status === UpdatedStatus.Installing ||
  status === UpdatedStatus.Installed;

/** Whether the desktop updater is already acting on an update in this session. */
const isUpdateInProgress = (status: UpdatedStatus) =>
  status === UpdatedStatus.Updating || status === UpdatedStatus.Installing || status === UpdatedStatus.Installed;

/**
 * Whether a check response has to be dropped because the stored state is further along.
 *
 * A check only reports whether a newer release exists; the download and installation progress
 * arrives on the desktop progress channel. Therefore a check response must never erase an update
 * that is downloading or installing, nor one that is already downloaded and waiting for its
 * restart, otherwise the user loses the button or the progress the check cannot restore.
 */
const keepsUpdateProgress = (current: UpdatedStatus, next: UpdatedStatus) => {
  if (isUpdateInProgress(current)) {
    return true;
  }
  if (current === UpdatedStatus.Updated) {
    // An update is downloaded and waiting for its restart: only a newer release that was
    // discovered now may replace it. A check that failed or found nothing must not erase it.
    return next !== UpdatedStatus.Available && next !== UpdatedStatus.Updated;
  }
  return false;
};

export const createHotUpdateAction: StateCreator<GlobalStore, [['zustand/devtools', never]], [], HotUpdateAction> = (
  set,
  get,
) => {
  const performCheck = async (trigger: UpdateCheckTrigger): Promise<boolean> => {
    const applyCheckStatus = (status: UpdatedStatus, version?: string) => {
      if (keepsUpdateProgress(get().updateDetail.status, status)) {
        return;
      }
      get().setUpdateDetail(version === undefined ? { status } : { status, version });
    };
    try {
      const res = await jcefApi.appCheckUpdate({ trigger, offlineActivation: get().offlineActivation });
      applyCheckStatus(res.status, res.version);
    } catch {
      // A failed check must not report a failed download or installation either.
      applyCheckStatus(UpdatedStatus.UpdateFailed);
    }
    return hasPendingUpdate(get().updateDetail.status);
  };

  return {
    updateAndRestartApp: async () => {
      if (!clientRuntime.enableAutoUpdate) {
        return;
      }
      if (get().updateDetail.status === UpdatedStatus.Updated) {
        get().setUpdateDetail({
          status: UpdatedStatus.Installing,
        });
        try {
          const accepted = await jcefApi.triggerInstallation();
          if (!accepted) {
            get().setUpdateDetail({
              status: UpdatedStatus.UpdateFailed,
            });
            return;
          }
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
    },
    setOfflineActivation: (value) => {
      if (get().offlineActivation !== value) {
        set({ offlineActivation: value });
      }
    },
    handleCheckUpdate: (trigger) => {
      if (!isDesktop || !clientRuntime.enableAutoUpdate) {
        return Promise.resolve(false);
      }
      if (pendingCheck) {
        return pendingCheck;
      }
      pendingCheck = performCheck(trigger).finally(() => {
        pendingCheck = null;
      });
      return pendingCheck;
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
