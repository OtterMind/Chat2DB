import { UpdatedStatus } from './status';
import type { IUpdateDetail } from '@/typings/settings';

type CheckUpdateRequest = () => Promise<IUpdateDetail>;
type SetUpdateDetail = (detail: IUpdateDetail) => void;

export const createCheckUpdateCoordinator = (requestCheck: CheckUpdateRequest, setUpdateDetail: SetUpdateDetail) => {
  let activeCheck: Promise<boolean> | undefined;

  return () => {
    if (activeCheck) {
      return activeCheck;
    }
    setUpdateDetail({ status: UpdatedStatus.Checking });
    activeCheck = requestCheck()
      .then((res) => {
        setUpdateDetail({
          status: res.status,
          version: res.version,
          releaseNotes: res.releaseNotes,
        });
        return res.status === UpdatedStatus.Available;
      })
      .catch(() => {
        setUpdateDetail({ status: UpdatedStatus.UpdateFailed });
        return false;
      })
      .finally(() => {
        activeCheck = undefined;
      });
    return activeCheck;
  };
};
