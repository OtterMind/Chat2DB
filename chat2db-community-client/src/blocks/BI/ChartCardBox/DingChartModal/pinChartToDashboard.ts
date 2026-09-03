import { IChartItem, IDashboardItem } from '@/typings';
import { appendLayoutItems } from '@/utils/dashboard';
import { chartDetailNormalization } from '../../utils/dataTreating';

export interface PinChartToDashboardDependencies {
  createChart: (chart: IChartItem) => Promise<number>;
  getDashboardById: (params: { id: number }) => Promise<IDashboardItem>;
  updateDashboard: (dashboard: IDashboardItem) => Promise<boolean>;
  // Resolves to `false` when the dashboard refresh request fails instead of rejecting.
  refreshCurrentDashboard: () => Promise<boolean>;
  closePinChartModal: () => void;
  showPinSuccessMessage: () => void;
  setSubmitLoading: (loading: boolean) => void;
}

/**
 * Pins a chart to a dashboard and then refreshes the current dashboard view.
 *
 * The success message is only shown when the follow-up refresh succeeded,
 * because `refreshCurrentDashboard` resolves to `false` on failure. Business
 * and network errors are surfaced by the global response interceptor; this
 * handler logs unexpected failures, settles with `false` instead of rejecting,
 * and always clears the submit loading state.
 */
export async function pinChartToDashboard(
  chartDetail: IChartItem | null | undefined,
  dashboard: IDashboardItem | null,
  dependencies: PinChartToDashboardDependencies,
): Promise<boolean> {
  if (!chartDetail || !dashboard) {
    return false;
  }

  dependencies.setSubmitLoading(true);
  try {
    const chartId = await dependencies.createChart(chartDetailNormalization(chartDetail));
    const dashboardDetail = await dependencies.getDashboardById({ id: dashboard.id });
    const newDashboardDetail = {
      ...dashboardDetail,
      schema: appendLayoutItems([chartId], {
        chartIds: dashboardDetail?.chartIds || [],
        schema: dashboardDetail?.schema || '',
      }),
      chartIds: [...(dashboardDetail?.chartIds || []), chartId],
    };
    if (!newDashboardDetail?.id) {
      return false;
    }
    await dependencies.updateDashboard(newDashboardDetail);
    dependencies.closePinChartModal();
    const refreshed = await dependencies.refreshCurrentDashboard();
    if (refreshed) {
      dependencies.showPinSuccessMessage();
    }
    return refreshed;
  } catch (error) {
    console.error(error);
    return false;
  } finally {
    dependencies.setSubmitLoading(false);
  }
}
