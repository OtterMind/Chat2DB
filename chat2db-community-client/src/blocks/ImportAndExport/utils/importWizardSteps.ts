/**
 * Step gating of the import wizard.
 *
 * The wizard itself owns the React state; this module only answers which step transition is
 * allowed and when the preview request has to be started, so the hard requirements ("step 1 needs
 * a file", "step 3 needs a loaded preview", "the format step loads its own preview") are testable
 * without mounting the component.
 */

export type ImportWizardStepKey = 'file' | 'format' | 'mapping' | 'confirm';

export const IMPORT_WIZARD_STEPS: ImportWizardStepKey[] = [
  'file',
  'format',
  'mapping',
  'confirm',
];

export const IMPORT_WIZARD_LAST_STEP = IMPORT_WIZARD_STEPS.length - 1;

/** The step that owns the preview request: entering it starts the preview for the current file. */
export const IMPORT_WIZARD_FORMAT_STEP = 1;

/** Option edits are debounced so typing a delimiter does not send one request per keystroke. */
export const IMPORT_PREVIEW_AUTO_LOAD_DELAY_MS = 300;

export type ImportWizardPreviewStatus = 'idle' | 'loading' | 'loaded' | 'failed';

export interface ImportWizardGateState {
  /** A source file (browser File or desktop path) has been selected. */
  fileSelected: boolean;
  /** Plain SQL script imports have no preview and no column mapping. */
  previewRequired: boolean;
  previewStatus: ImportWizardPreviewStatus;
  /** Mapping must be valid before a data-file import reaches confirmation. */
  mappingValid?: boolean;
}

/** What the wizard has already asked for, so an automatically failed request is not retried. */
export interface ImportPreviewAttempt {
  /** Configuration key of the last preview the wizard started by itself. */
  attemptedKey?: string;
  /** Configuration key of the file and options the wizard currently holds. */
  currentPreviewKey: string;
}

const IMPORT_WIZARD_STEP_TITLE_KEYS = {
  file: 'workspace.importExport.wizard.stepFile',
  format: 'workspace.importExport.wizard.stepFormat',
  mapping: 'workspace.importExport.wizard.stepMapping',
  confirm: 'workspace.importExport.wizard.stepConfirm',
} as const;

/** i18n key of the step name, for the wizard title; null when the step is out of range. */
export type ImportWizardStepTitleKey =
  | (typeof IMPORT_WIZARD_STEP_TITLE_KEYS)[ImportWizardStepKey]
  | 'workspace.importExport.wizard.stepSqlConfirm';

export const getImportWizardStepTitleKey = (step: number, isSql = false): ImportWizardStepTitleKey | null => {
  if (isSql && step === IMPORT_WIZARD_LAST_STEP) return 'workspace.importExport.wizard.stepSqlConfirm';
  const stepKey = IMPORT_WIZARD_STEPS[step];
  return stepKey ? IMPORT_WIZARD_STEP_TITLE_KEYS[stepKey] : null;
};

/** SQL has only file selection and confirmation; its encoding is edited on confirmation. */
export const getImportWizardNextStep = (step: number, isSql = false): number =>
  isSql ? IMPORT_WIZARD_LAST_STEP : Math.min(step + 1, IMPORT_WIZARD_LAST_STEP);

export const getImportWizardPreviousStep = (step: number, isSql = false): number =>
  isSql ? 0 : Math.max(0, step - 1);

export const isImportWizardLastStep = (step: number): boolean => step >= IMPORT_WIZARD_LAST_STEP;

/** Whether the "next" button leaves the given step; earlier steps must already have passed. */
export const canAdvanceImportWizard = (step: number, state: ImportWizardGateState): boolean => {
  switch (step) {
    case 0:
      return state.fileSelected;
    case 1:
      return !state.previewRequired || state.previewStatus === 'loaded';
    case 2:
      return !state.previewRequired || state.mappingValid === true;
    default:
      return true;
  }
};

/**
 * Whether the footer's "next" button is clickable. It follows the gate above, except on the
 * format step: a preview that failed must not lock the wizard, so the user can retry it by hand.
 */
export const canPressImportWizardNext = (step: number, state: ImportWizardGateState): boolean => {
  if (step === IMPORT_WIZARD_FORMAT_STEP && state.previewRequired && state.previewStatus === 'failed') {
    return true;
  }
  return canAdvanceImportWizard(step, state);
};

/** Step 2 -> 3 must call the preview API, but only while the current options are not loaded yet. */
export const shouldLoadImportPreviewOnAdvance = (step: number, state: ImportWizardGateState): boolean =>
  step === IMPORT_WIZARD_FORMAT_STEP && state.previewRequired && state.previewStatus !== 'loaded';

/**
 * Whether the wizard starts a preview by itself. The format step needs a loaded preview to be left,
 * so it requests one on entry; an option change requests the new configuration again, while the
 * same configuration that already failed is left to the manual retry of the "next" button.
 */
export const shouldAutoLoadImportPreview = (
  step: number,
  state: ImportWizardGateState,
  attempt: ImportPreviewAttempt,
): boolean =>
  step === IMPORT_WIZARD_FORMAT_STEP &&
  state.previewRequired &&
  state.previewStatus !== 'loading' &&
  state.previewStatus !== 'loaded' &&
  attempt.attemptedKey !== attempt.currentPreviewKey;
