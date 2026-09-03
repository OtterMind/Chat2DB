import {
  captureDashboardChartDeleteMutation,
  isDashboardMutationCurrent,
  resolveDashboardChartDeleteTarget,
  resolveDashboardMutationState,
} from './dashboardMutation';
import { StateCreator } from 'zustand';
import { DashboardStore } from '../../store';
import { CommonState } from './initialState';
import {
  createDashboard,
  deleteDashboard,
  getDashboardList,
  updateDashboard,
  deleteChart,
  getDashboardById,
} from '@/service/dashboard';
import i18n from '@/i18n';
import { staticMessage } from '@chat2db/ui';
import { filterSchemaByChartIds } from '@/utils/dashboard';

export interface CommonAction {
  /** Set up Dashboard list */
  setDashboardList: (dashboardList: CommonState['dashboardList']) => void;
  /** Request Dashboard list */
  queryDashboardList: (dashboardId?: number) => void;
  /** Set the current Dashboard */
  setCurrentDashboard: (dashboard: CommonState['currentDashboard']) => void;
  /** Update current Dashboard */
  updateDashboard: (dashboard: CommonState['currentDashboard']) => void;
  /** Create Dashboard */
  createDashboard: (dashboard: CommonState['currentDashboard']) => void;
  /** Delete Dashboard */
  deleteDashboard: (id: number) => Promise<void>;
  // Delete DashboardItem
  deleteChart: (id: number) => Promise<void>;
  // Change the layout of the dashboard
  updateDashboardLayout: (layout: any) => void;
  getDashboardById: (id: number) => Promise<void>;
  // Refresh the current report details page
  refreshCurrentDashboard: () => Promise<boolean>;
}

export const createCommonAction: StateCreator<DashboardStore, [['zustand/devtools', never]], [], CommonAction> = (
  set,
  get,
) => ({
  /** Set up Dashboard list */
  setDashboardList: (dashboardList) => {
    set({ dashboardList });
  },
  createDashboard: async (dashboard) => {
    if (!dashboard) return;
    try {
      const id = await createDashboard(dashboard);
      get().setSettingDashboard(undefined);
      const newDashboard = { ...dashboard, id };
      set({
        dashboardList: [newDashboard, ...get().dashboardList],
        currentDashboard: newDashboard,
      });
      staticMessage.success(i18n('common.tips.createSuccess'));
    } catch (_error) {
      staticMessage.error('创建失败');
    }
  },
  queryDashboardList: async (dashboardId) => {
    const pageParams = get().dashboardListParams;
    const res = await getDashboardList(pageParams);
    if (res.data) {
      set({
        dashboardList: [...get().dashboardList, ...res.data],
        dashboardListParams: { ...pageParams, pageNo: pageParams.pageNo + 1, hasNextPage: !!res.hasNextPage },
      });

      const { currentDashboard } = get();
      if (!currentDashboard && !dashboardId && res.data?.[0]?.id) {
        get().getDashboardById(res.data?.[0]?.id);
      }
    }
  },
  setCurrentDashboard: async (dashboard) => {
    set({ currentDashboard: dashboard });
  },
  updateDashboard: (dashboard) => {
    if (!dashboard) return;
    updateDashboard(dashboard).then(() => {
      const currentDashboard = get().currentDashboard;
      set(resolveDashboardMutationState(currentDashboard, get().dashboardList, dashboard));
      if (isDashboardMutationCurrent(currentDashboard, dashboard)) {
        get().setSettingDashboard(undefined);
      }
    });
  },
  deleteDashboard: async (id) => {
    return deleteDashboard({ id }).then(() => {
      const dashboardList = get().dashboardList.filter((item) => item.id !== id);
      set({
        dashboardList: dashboardList,
      });
      // If you delete the currently selected dashboard
      const { currentDashboard } = get();
      if (currentDashboard?.id === id) {
        get().setCurrentDashboard(dashboardList[0]);
      }
      staticMessage.success(i18n('common.text.successfullyDelete'));
    });
  },
  updateDashboardLayout: (layout) => {
    const currentDashboard: any = get().currentDashboard || {};
    // set({ currentDashboard: { ...currentDashboard, schema: JSON.stringify(layout) } });
    get().updateDashboard({ ...currentDashboard, schema: JSON.stringify(layout) });
  },
  deleteChart: (id) => {
    const mutation = captureDashboardChartDeleteMutation(get().currentDashboard, id);
    return deleteChart({ id }).then(() => {
      const updatedDashboard = resolveDashboardChartDeleteTarget(
        mutation,
        get().currentDashboard,
        filterSchemaByChartIds,
      );
      if (!updatedDashboard) return;
      set(resolveDashboardMutationState(get().currentDashboard, get().dashboardList, updatedDashboard));
      get().updateDashboard(updatedDashboard);
    });
  },
  getDashboardById: (id) => {
    // This is not possible on the desktop
    // if (!checkIsSharePage()) {
    //   history.pushState(null, '', `/dashboard/${id}`);
    // }
    // set({ currentDashboard: null });
    return getDashboardById({ id }).then((res) => {
      set({ currentDashboard: res });
    });
  },
  refreshCurrentDashboard: () => {
    const currentDashboardId = get().currentDashboard?.id;
    return new Promise((resolve) => {
      if (!currentDashboardId) {
        resolve(false);
        return;
      }
      get()
        .getDashboardById(currentDashboardId)
        .then(() => {
          resolve(true);
        });
    });
  },
});
