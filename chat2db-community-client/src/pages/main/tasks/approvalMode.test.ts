import assert from 'node:assert/strict';

import { approvalModeColor, effectiveApprovalMode, normalizeApprovalMode } from './approvalMode';

assert.equal(normalizeApprovalMode(undefined), 'RISK_BASED');
assert.equal(normalizeApprovalMode('NEVER'), 'NEVER');
assert.equal(normalizeApprovalMode('ALWAYS'), 'ALWAYS');
assert.equal(approvalModeColor('NEVER'), 'default');
assert.equal(approvalModeColor('RISK_BASED'), 'blue');
assert.equal(approvalModeColor('ALWAYS'), 'orange');
assert.equal(effectiveApprovalMode('NEVER', 'ALWAYS'), 'ALWAYS');
assert.equal(effectiveApprovalMode('ALWAYS', 'NEVER'), 'ALWAYS');
assert.equal(effectiveApprovalMode('NEVER', 'RISK_BASED'), 'RISK_BASED');
