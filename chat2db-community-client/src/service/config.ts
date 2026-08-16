import createRequest from './base';

export interface ILatestVersion {
  /**
   * Desktop
   */
  desktop: boolean;
  /**
   * new version
   */
  version: string;
  /**
   * Hot update package address, which can be used to determine whether it is hot update
   */
  hotUpgradeUrl: null | string;
  /**
   *Whether the user chooses manual update or automatic update
   */
  type: 'manual' | 'auto';
  /**
   *Does it need to be updated?
   */
  needUpdate?: boolean;
  /**
   *Download address
   */
  downloadLink?: null | string;
  /**
   * Update log
   */
  updateLog?: null | string;
  /**
   * Whitelist, for testing
   */
  whiteList?: null | string;
}

// Return the latest version information, or null when no update is available.
const getLatestVersion = createRequest<{ currentVersion: string }, ILatestVersion>('/api/system/get_latest_version', {
  method: 'get',
  errorLevel: false,
});

// Check whether the latest package backend is successfully downloaded
const isUpdateSuccess = createRequest<{ version: string }, boolean>('/api/system/is_update_success', {
  method: 'get',
});

// Tell the backend to download the latest package
const updateDesktopVersion = createRequest<ILatestVersion, boolean>('/api/system/update_desktop_version', {
  method: 'post',
});

// Tell the backend to download the latest package
const setAppUpdateType = createRequest<ILatestVersion['type'], boolean>('/api/system/set_update_type', {
  method: 'post',
});

export default {
  getLatestVersion,
  isUpdateSuccess,
  updateDesktopVersion,
  setAppUpdateType,
};
