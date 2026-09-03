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
  } catch (error) {
    // Business and network errors are surfaced by the global response interceptor; keep the
    // console log so unexpected programming errors are not swallowed silently.
    console.error(error);
    return false;
  }
}
