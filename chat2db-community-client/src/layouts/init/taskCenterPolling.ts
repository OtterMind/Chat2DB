interface TaskCenterPollingOptions {
  enabled: boolean;
  desktop: boolean;
  serviceReady: boolean;
}

export const shouldAutoPollTaskCenter = ({ enabled, desktop, serviceReady }: TaskCenterPollingOptions): boolean =>
  enabled && (!desktop || serviceReady);
