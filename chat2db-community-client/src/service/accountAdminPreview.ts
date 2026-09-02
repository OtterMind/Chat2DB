import type { AccountActionType } from './accountAdmin';

export interface AccountDefinerImpact {
  objectType: string;
  schemaName: string;
  objectName: string;
  definer: string;
}

export interface AccountPreview {
  actionType: AccountActionType;
  sql: string;
  previewToken: string;
  oldAccountSql?: string;
  newAccountSql?: string;
  definerEnumerationComplete?: boolean;
  warningCodes?: string[];
  definerImpacts?: AccountDefinerImpact[];
}

export function formatAccountDefinerImpact(impact: AccountDefinerImpact) {
  return `${impact.objectType} ${impact.schemaName}.${impact.objectName} (${impact.definer})`;
}
