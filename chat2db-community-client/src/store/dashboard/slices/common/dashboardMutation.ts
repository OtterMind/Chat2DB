import type { IDashboardItem } from '@/typings';

type DashboardSchemaFilter = (chartIds?: number[], schema?: string) => string;

export interface DashboardChartDeleteMutation {
  chartId: number;
  dashboardId: number;
  dashboardSnapshot: IDashboardItem;
}

export function isDashboardMutationCurrent(
  currentDashboard: IDashboardItem | null | undefined,
  updatedDashboard: IDashboardItem,
) {
  return updatedDashboard.id != null && currentDashboard?.id === updatedDashboard.id;
}

export function resolveDashboardMutationState(
  currentDashboard: IDashboardItem | null | undefined,
  dashboardList: IDashboardItem[],
  updatedDashboard: IDashboardItem,
) {
  const ownsCurrentDashboard = isDashboardMutationCurrent(currentDashboard, updatedDashboard);
  const updatedDashboardId = updatedDashboard.id;

  return {
    currentDashboard: ownsCurrentDashboard
      ? { ...currentDashboard, ...updatedDashboard }
      : currentDashboard,
    dashboardList:
      updatedDashboardId == null
        ? dashboardList
        : dashboardList.map((item) =>
            item.id === updatedDashboardId ? { ...item, ...updatedDashboard } : item,
          ),
  };
}

export function captureDashboardChartDeleteMutation(
  currentDashboard: IDashboardItem | null | undefined,
  chartId: number,
): DashboardChartDeleteMutation | null {
  if (currentDashboard?.id == null) {
    return null;
  }
  return {
    chartId,
    dashboardId: currentDashboard.id,
    dashboardSnapshot: {
      ...currentDashboard,
      chartIds: currentDashboard.chartIds ? [...currentDashboard.chartIds] : currentDashboard.chartIds,
    },
  };
}

export function resolveDashboardChartDeleteTarget(
  mutation: DashboardChartDeleteMutation | null,
  currentDashboard: IDashboardItem | null | undefined,
  filterSchema: DashboardSchemaFilter,
): IDashboardItem | null {
  if (!mutation) {
    return null;
  }
  const targetDashboard =
    currentDashboard?.id === mutation.dashboardId
      ? currentDashboard
      : mutation.dashboardSnapshot;
  const chartIds = targetDashboard.chartIds?.filter((chartId) => chartId !== mutation.chartId);
  return {
    ...targetDashboard,
    chartIds,
    schema: filterSchema(chartIds, targetDashboard.schema),
  };
}
