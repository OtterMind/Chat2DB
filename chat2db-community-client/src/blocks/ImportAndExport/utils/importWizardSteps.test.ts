import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canAdvanceImportWizard,
  canPressImportWizardNext,
  getImportWizardStepTitleKey,
  getImportWizardNextStep,
  getImportWizardPreviousStep,
  IMPORT_WIZARD_LAST_STEP,
  IMPORT_WIZARD_STEPS,
  isImportWizardLastStep,
  shouldAutoLoadImportPreview,
  shouldLoadImportPreviewOnAdvance,
  type ImportPreviewAttempt,
  type ImportWizardGateState,
} from './importWizardSteps';

const state = (patch: Partial<ImportWizardGateState> = {}): ImportWizardGateState => ({
  fileSelected: false,
  previewRequired: true,
  previewStatus: 'idle',
  mappingValid: true,
  ...patch,
});

test('keeps the wizard order and marks the last step', () => {
  assert.deepEqual(IMPORT_WIZARD_STEPS, ['file', 'format', 'mapping', 'confirm']);
  assert.equal(IMPORT_WIZARD_LAST_STEP, 3);
  assert.equal(isImportWizardLastStep(2), false);
  assert.equal(isImportWizardLastStep(3), true);
});

test('requires a selected file before leaving the file step', () => {
  assert.equal(canAdvanceImportWizard(0, state()), false);
  assert.equal(canAdvanceImportWizard(0, state({ fileSelected: true })), true);
});

test('requires a loaded preview before entering the mapping step', () => {
  assert.equal(canAdvanceImportWizard(1, state({ fileSelected: true, previewStatus: 'idle' })), false);
  assert.equal(canAdvanceImportWizard(1, state({ fileSelected: true, previewStatus: 'loading' })), false);
  assert.equal(canAdvanceImportWizard(1, state({ fileSelected: true, previewStatus: 'failed' })), false);
  assert.equal(canAdvanceImportWizard(1, state({ fileSelected: true, previewStatus: 'loaded' })), true);
});

test('lets a SQL script import skip the preview requirement', () => {
  const sqlState = state({ fileSelected: true, previewRequired: false, previewStatus: 'idle' });
  assert.equal(canAdvanceImportWizard(1, sqlState), true);
  assert.equal(shouldLoadImportPreviewOnAdvance(1, sqlState), false);
});

test('SQL goes directly to confirmation and returns to its selected file', () => {
  assert.equal(getImportWizardNextStep(0, true), IMPORT_WIZARD_LAST_STEP);
  assert.equal(getImportWizardPreviousStep(IMPORT_WIZARD_LAST_STEP, true), 0);
  assert.equal(getImportWizardNextStep(IMPORT_WIZARD_LAST_STEP, true), IMPORT_WIZARD_LAST_STEP);
  assert.equal(getImportWizardPreviousStep(0, true), 0);
  assert.equal(getImportWizardStepTitleKey(IMPORT_WIZARD_LAST_STEP, true),
    'workspace.importExport.wizard.stepSqlConfirm');
});

test('data files retain format, mapping and confirmation in both directions', () => {
  let step = 0;
  const forward = [step];
  for (let count = 0; count < 3; count += 1) {
    step = getImportWizardNextStep(step);
    forward.push(step);
  }
  assert.deepEqual(forward, [0, 1, 2, 3]);
  assert.deepEqual(forward.map((current) => getImportWizardPreviousStep(current)), [0, 0, 1, 2]);
  assert.equal(getImportWizardNextStep(IMPORT_WIZARD_LAST_STEP), IMPORT_WIZARD_LAST_STEP);
});

test('loads the preview exactly when leaving the format step without one', () => {
  assert.equal(shouldLoadImportPreviewOnAdvance(0, state({ previewStatus: 'idle' })), false);
  assert.equal(shouldLoadImportPreviewOnAdvance(1, state({ previewStatus: 'idle' })), true);
  assert.equal(shouldLoadImportPreviewOnAdvance(1, state({ previewStatus: 'failed' })), true);
  assert.equal(shouldLoadImportPreviewOnAdvance(1, state({ previewStatus: 'loaded' })), false);
  assert.equal(shouldLoadImportPreviewOnAdvance(2, state({ previewStatus: 'idle' })), false);
});

test('valid mappings may advance and confirmation remains available', () => {
  assert.equal(canAdvanceImportWizard(2, state()), true);
  assert.equal(canAdvanceImportWizard(3, state()), true);
});

test('names the step the wizard is currently showing', () => {
  assert.equal(getImportWizardStepTitleKey(0), 'workspace.importExport.wizard.stepFile');
  assert.equal(getImportWizardStepTitleKey(1), 'workspace.importExport.wizard.stepFormat');
  assert.equal(getImportWizardStepTitleKey(2), 'workspace.importExport.wizard.stepMapping');
  assert.equal(getImportWizardStepTitleKey(3), 'workspace.importExport.wizard.stepConfirm');
  assert.equal(getImportWizardStepTitleKey(4), null);
});

test('starts the preview by itself on the format step', () => {
  const attempt = (patch: Partial<ImportPreviewAttempt> = {}): ImportPreviewAttempt => ({
    currentPreviewKey: 'file-1:csv-default',
    ...patch,
  });
  assert.equal(shouldAutoLoadImportPreview(0, state({ fileSelected: true }), attempt()), false);
  assert.equal(shouldAutoLoadImportPreview(1, state({ fileSelected: true }), attempt()), true);
  assert.equal(
    shouldAutoLoadImportPreview(1, state({ fileSelected: true, previewRequired: false }), attempt()),
    false,
    'a SQL script has no preview',
  );
});

test('does not retry the same configuration but follows option changes', () => {
  const loaded = state({ fileSelected: true, previewStatus: 'loaded' });
  assert.equal(
    shouldAutoLoadImportPreview(1, loaded, { attemptedKey: undefined, currentPreviewKey: 'k1' }),
    false,
  );
  assert.equal(
    shouldAutoLoadImportPreview(1, state({ fileSelected: true, previewStatus: 'loading' }), {
      attemptedKey: 'k1',
      currentPreviewKey: 'k2',
    }),
    false,
    'a request in flight is not duplicated',
  );
  assert.equal(
    shouldAutoLoadImportPreview(1, state({ fileSelected: true, previewStatus: 'failed' }), {
      attemptedKey: 'k1',
      currentPreviewKey: 'k1',
    }),
    false,
    'a failed configuration is only retried by hand',
  );
  assert.equal(
    shouldAutoLoadImportPreview(1, state({ fileSelected: true, previewStatus: 'failed' }), {
      attemptedKey: 'k1',
      currentPreviewKey: 'k2',
    }),
    true,
    'changing an option asks for the new configuration',
  );
});

test('keeps the next button on the format step clickable after a failed preview', () => {
  assert.equal(canPressImportWizardNext(1, state({ fileSelected: true, previewStatus: 'idle' })), false);
  assert.equal(canPressImportWizardNext(1, state({ fileSelected: true, previewStatus: 'loading' })), false);
  assert.equal(canPressImportWizardNext(1, state({ fileSelected: true, previewStatus: 'loaded' })), true);
  assert.equal(
    canPressImportWizardNext(1, state({ fileSelected: true, previewStatus: 'failed' })),
    true,
    'a failed preview must not lock the wizard',
  );
  // The other steps keep the gate unchanged.
  assert.equal(canPressImportWizardNext(0, state()), false);
  assert.equal(canPressImportWizardNext(0, state({ fileSelected: true })), true);
  assert.equal(canPressImportWizardNext(2, state()), true);
  assert.equal(canPressImportWizardNext(3, state()), true);
});

test('blocks leaving mapping until corrected, while keeping SQL unaffected', () => {
  assert.equal(canPressImportWizardNext(2, state({ mappingValid: false })), false);
  assert.equal(canAdvanceImportWizard(2, state({ mappingValid: false })), false);
  assert.equal(canPressImportWizardNext(2, state({ mappingValid: true })), true);
  assert.equal(canAdvanceImportWizard(2, state({ previewRequired: false, mappingValid: false })), true);
});
