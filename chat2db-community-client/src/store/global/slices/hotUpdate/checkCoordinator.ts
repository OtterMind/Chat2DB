import { UpdatedStatus } from './status';
import type { IUpdateDetail } from '@/typings/settings';

type CheckUpdateRequest = () => Promise<IUpdateDetail>;
type SetUpdateDetail = (detail: IUpdateDetail) => void;

export const CHECK_COOLDOWN_MS = 5_000;

export const createCheckUpdateCoordinator = (requestCheck: CheckUpdateRequest, setUpdateDetail: SetUpdateDetail) => {
  let activeCheck: Promise<boolean> | undefined;
  let lastCheckCompletedAt = Number.NEGATIVE_INFINITY;

  return () => {
    if (activeCheck) {
      return activeCheck;
    }
    if (Date.now() - lastCheckCompletedAt < CHECK_COOLDOWN_MS) {
      return Promise.resolve(false);
    }
    setUpdateDetail({ status: UpdatedStatus.Checking });
    activeCheck = requestCheck()
      .then((res) => {
        setUpdateDetail({
          status: res.status,
          version: res.version,
          releaseNotes: res.releaseNotes,
          releasePageUrl: res.releasePageUrl,
          failureStage: res.failureStage,
          failureReason: res.failureReason,
        });
        return res.status === UpdatedStatus.Available;
      })
      .catch(() => {
        setUpdateDetail({ status: UpdatedStatus.UpdateFailed, failureStage: 'CHECK', failureReason: 'UNKNOWN' });
        return false;
      })
      .finally(() => {
        activeCheck = undefined;
        lastCheckCompletedAt = Date.now();
      });
    return activeCheck;
  };
};
