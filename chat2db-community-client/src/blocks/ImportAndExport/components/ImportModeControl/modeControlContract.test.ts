import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const control = fs.readFileSync(path.resolve(__dirname, 'index.tsx'), 'utf8');
const typings = fs.readFileSync(
  path.resolve(__dirname, '../../../../typings/importExport.ts'),
  'utf8',
);

// ImportExecutionMode is a closed union and this repo has no standalone type-check step
// (lint is eslint + stylelint, umi build runs no fork-ts-checker), so a merely plausible
// spelling slips through the whole pipeline. The control used to compare against 'FAST',
// which is not in the union: the switch therefore never reflected ULTRA_FAST and onChange
// emitted a mode the backend could never receive. Pin the value domain instead.
const union = typings.match(/export type ImportExecutionMode = ([^;]+);/);
assert.ok(union, 'ImportExecutionMode must stay a declared union in typings/importExport.ts');
const modes = (union![1].match(/'([^']+)'/g) ?? []).map((value) => value.replace(/'/g, ''));
assert.ok(
  modes.includes('ULTRA_FAST') && modes.includes('STANDARD'),
  `expected the union to offer ULTRA_FAST and STANDARD, got ${JSON.stringify(modes)}`,
);

const emitted = [...control.matchAll(/onChange\('([^']+)'\)/g)].map((match) => match[1]);
assert.ok(emitted.length > 0, 'the control must emit at least one mode');
for (const mode of emitted) {
  assert.ok(
    modes.includes(mode),
    `ImportModeControl emits '${mode}', which is not a valid ImportExecutionMode (${modes.join(', ')})`,
  );
}

const compared = [...control.matchAll(/value === '([^']+)'/g)].map((match) => match[1]);
assert.ok(compared.length > 0, 'the control must render from the mode value');
for (const mode of compared) {
  assert.ok(
    modes.includes(mode),
    `ImportModeControl compares against '${mode}', which is not a valid ImportExecutionMode (${modes.join(', ')})`,
  );
}

// The mode the switch renders as enabled must be exactly the mode the dialog can turn on,
// otherwise the toggle silently reverts.
assert.ok(
  compared.filter((mode) => mode === 'ULTRA_FAST').length === 1,
  'the switch must render exactly one enabled mode',
);
assert.ok(
  emitted.filter((mode) => mode === 'ULTRA_FAST').length === 1,
  'confirming the dialog must enable exactly the mode the switch renders as enabled',
);

// The wizard carries the R1 acknowledgement only in parallel mode. It guarded on a 'FAST'
// literal that is not in the union, so the condition was never true and the confirmation
// silently never reached the backend. Pin the wizard's comparison against the union as well.
const wizard = fs.readFileSync(
  path.resolve(__dirname, '../MultiTableImportWizard/taskParams.ts'),
  'utf8',
);
const guarded = [...wizard.matchAll(/settings\.mode === '([^']+)'/g)].map((match) => match[1]);
assert.ok(guarded.length > 0, 'the wizard must guard the acknowledgement on the execution mode');
for (const mode of guarded) {
  assert.ok(
    modes.includes(mode),
    `MultiTableImportWizard guards on '${mode}', which is not a valid ImportExecutionMode (${modes.join(', ')})`,
  );
}
assert.ok(
  guarded.includes('ULTRA_FAST'),
  'the wizard must send confirmedNoStrongRelations when it asks for parallel execution',
);

console.log('import mode control contract tests passed');
