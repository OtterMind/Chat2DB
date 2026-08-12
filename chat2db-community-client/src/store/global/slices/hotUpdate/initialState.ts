import { UpdatedStatus } from '@/constants/settings';
import { isCommunityEnv } from '@/utils/env';
import { IHotUpdateConfig, IUpdateDetail } from '@/typings/settings';

export interface HotUpdateState {
  hotUpdateConfig: IHotUpdateConfig;
  updateDetail: IUpdateDetail;
}

export const initialHotUpdateState: HotUpdateState = {
  hotUpdateConfig: {
    /**
     * Do you want to remind me?
     */
    // Community desktop stays offline-first until the user explicitly opts in.
    remindMe: !isCommunityEnv,
    /**
     * Whether to download automatically
     */
    autoDownload: false,
    /**
     * Whether to install automatically
     */
    autoInstall: false,
  },
  updateDetail: {
    status: UpdatedStatus.Default,
  },
};
