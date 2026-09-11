export interface ClientWorkspaceContext {
  id: number;
  name: string;
  kind: 'local' | 'shared' | 'managed';
  permissions: readonly string[];
  roleCodes: readonly string[];
  ownerId?: number;
  vip?: boolean;
  createTime?: number;
}

export interface ClientIdentity {
  id: number;
  displayName: string;
  avatar?: string;
  email?: string;
  activated?: boolean;
  vip?: boolean;
  currentWorkspace?: ClientWorkspaceContext;
}
