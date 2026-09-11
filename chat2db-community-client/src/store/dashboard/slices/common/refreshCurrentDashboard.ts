type DashboardLoader = (dashboardId: number) => Promise<void>;

export async function runDashboardRefresh(
  currentDashboardId: number | undefined,
  loadDashboard: DashboardLoader,
): Promise<boolean> {
  if (currentDashboardId === undefined) {
    return false;
  }

  await loadDashboard(currentDashboardId);
  return true;
}
