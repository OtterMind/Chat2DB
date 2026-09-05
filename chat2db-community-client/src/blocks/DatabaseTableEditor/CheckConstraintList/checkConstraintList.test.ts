import { EditColumnOperationType } from '@/constants/editTable';
import type { ICheckConstraintItem } from '@/typings';
import {
  createCheckConstraintDraft,
  markCheckConstraintDeleted,
  markCheckConstraintUpdated,
  prepareCheckConstraintsForSubmit,
  visibleCheckConstraints,
} from './checkConstraintList';

function assertEqual(actual: any, expected: any, message: string) {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  if (actualJson !== expectedJson) {
    throw new Error(`${message}: expected ${expectedJson}, got ${actualJson}`);
  }
}

const existing = {
  key: 'existing-key',
  name: 'ck_payment_amount',
  expression: 'amount >= 0',
  enforced: true,
  editStatus: null,
} as ICheckConstraintItem;

const added = createCheckConstraintDraft('new-key');
assertEqual(
  added,
  {
    key: 'new-key',
    name: '',
    expression: '',
    enforced: true,
    editStatus: EditColumnOperationType.Add,
  },
  'new check constraints default to enforced ADD rows',
);

assertEqual(
  markCheckConstraintUpdated(existing, 'expression', 'amount > 0'),
  {
    ...existing,
    expression: 'amount > 0',
    editStatus: EditColumnOperationType.Modify,
  },
  'editing an existing check constraint marks it MODIFY',
);

assertEqual(
  markCheckConstraintUpdated(added, 'name', 'ck_payment_positive').editStatus,
  EditColumnOperationType.Add,
  'editing a new check constraint keeps ADD status',
);

const deletedExisting = markCheckConstraintDeleted(existing);
if (!deletedExisting) {
  throw new Error('existing check constraint should be preserved as a DELETE row');
}
assertEqual(
  deletedExisting.editStatus,
  EditColumnOperationType.Delete,
  'deleting an existing check constraint marks it DELETE for backend DDL',
);

assertEqual(
  markCheckConstraintDeleted(added),
  null,
  'deleting an unsaved check constraint removes it from submit payload',
);

assertEqual(
  visibleCheckConstraints([existing, deletedExisting]).map((item) => item.name),
  ['ck_payment_amount'],
  'deleted check constraints remain in state but are hidden from the editor table',
);

assertEqual(
  prepareCheckConstraintsForSubmit([deletedExisting, { ...added, name: 'ck_new', expression: 'amount < 100' }], {
    databaseName: 'test_db',
    schemaName: null,
    tableName: 'payment',
  }),
  [
    {
      name: 'ck_payment_amount',
      expression: 'amount >= 0',
      enforced: true,
      editStatus: EditColumnOperationType.Delete,
      databaseName: 'test_db',
      schemaName: null,
      tableName: 'payment',
    },
    {
      name: 'ck_new',
      expression: 'amount < 100',
      enforced: true,
      editStatus: EditColumnOperationType.Add,
      databaseName: 'test_db',
      schemaName: null,
      tableName: 'payment',
    },
  ],
  'submit payload preserves deleted rows, strips UI keys, and includes table metadata',
);

console.log('checkConstraintList tests passed');
