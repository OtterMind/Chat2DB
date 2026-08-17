import { UpdatedStatus } from '@/constants/settings';
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
    remindMe: true,
    /**
     * Whether to download automatically
     */
    autoDownload: false,
    /**
     * Whether to install automatically
     */
    autoInstall: false,
    /**
     * Whether beta versions are included in update checks
     */
    receiveBeta: false,
  },
  updateDetail: {
    status: UpdatedStatus.Default,
  },
};
