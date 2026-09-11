export enum EditColumnOperationType { 
  // New
  Add = 'ADD',
  // Modify
  Modify = 'MODIFY',
  // Delete
  Delete = 'DELETE',
}

// nullable
export enum NullableType {
  // Cannot be empty
  NotNull = 0,
  // Can be null
  Null = 1,
}

export const MYSQL_PRIMARY_INDEX_TYPE = 'Primary' as const;

export const MYSQL_VISIBILITY = {
  VISIBLE: { label: 'VISIBLE', value: true },
  INVISIBLE: { label: 'INVISIBLE', value: false },
} as const;
