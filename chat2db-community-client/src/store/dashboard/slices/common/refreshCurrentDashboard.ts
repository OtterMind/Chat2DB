type DashboardLoader = (dashboardId: number) => Promise<void>;

export async function runDashboardRefresh(
  currentDashboardId: number | undefined,
  loadDashboard: DashboardLoader,
): Promise<boolean> {
  if (currentDashboardId === undefined) {
    return false;
  }

  try {
    await loadDashboard(currentDashboardId);
    return true;
  } catch (_error) {
    return false;
  }
}
