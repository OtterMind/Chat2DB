export function createEventTreeNodeKey(params: {
  dataSourceId?: string | number | null;
  databaseName?: string | null;
  eventName?: string | null;
}) {
  return [
    `dataSource_${params.dataSourceId}`,
    `database_${params.databaseName}`,
    'events_chat2dbCatalogue',
    `event_${params.eventName}`,
  ].join('-');
}

export function supportsEventTree(databaseType?: string | null) {
  return databaseType === 'MYSQL';
}

export function createEventTreeNodeDescription(
  status: string | null | undefined,
  schedulerEnabled: boolean | undefined,
  schedulerDisabledLabel: string,
) {
  const parts = [status?.trim(), schedulerEnabled === false ? schedulerDisabledLabel : undefined].filter(Boolean);
  return parts.length ? parts.join(' - ') : undefined;
}
