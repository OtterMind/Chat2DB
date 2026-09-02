import assert from 'node:assert/strict';
import type { AccountActionType } from './accountAdmin';
import { formatAccountDefinerImpact, type AccountPreview } from './accountAdminPreview';

const preview: AccountPreview = {
  actionType: 'RENAME_USER' as AccountActionType,
  sql: "RENAME USER 'old''user'@'10.%' TO 'new'@'localhost'",
  previewToken: 'token',
  oldAccountSql: "'old''user'@'10.%'",
  newAccountSql: "'new'@'localhost'",
  definerEnumerationComplete: false,
  warningCodes: ['mysql.account.renameImpactWarning', 'mysql.account.definerEnumerationIncomplete'],
  definerImpacts: [
    {
      objectType: 'TRIGGER',
      schemaName: 'app',
      objectName: 'orders_bi',
      definer: "old'user@10.%",
    },
  ],
};

assert.equal(preview.oldAccountSql, "'old''user'@'10.%'");
assert.equal(preview.newAccountSql, "'new'@'localhost'");
assert.equal(preview.definerEnumerationComplete, false);
assert.deepEqual(preview.warningCodes, ['mysql.account.renameImpactWarning', 'mysql.account.definerEnumerationIncomplete']);
assert.equal(formatAccountDefinerImpact(preview.definerImpacts![0]), "TRIGGER app.orders_bi (old'user@10.%)");

console.log('Account admin preview tests passed');
