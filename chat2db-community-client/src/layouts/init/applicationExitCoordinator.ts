export interface ApplicationExitConfirmation {
  activeTaskCount: number;
  onCancel: () => void;
  onConfirm: () => Promise<void>;
}

interface ApplicationExitOperations {
  confirmDirtyEditors: () => Promise<boolean>;
  finalizeBeforeClose?: () => Promise<boolean>;
  shouldManageTasks: () => boolean;
  getActiveTaskCount: () => Promise<number>;
  prepareUserExit: () => Promise<void>;
  abortUserExit: () => Promise<void>;
  confirmCloseWindow: () => Promise<boolean>;
  cancelApplicationExit: () => Promise<boolean>;
  requestConfirmation: (confirmation: ApplicationExitConfirmation) => void;
  onCancel: () => void;
}

const finishApplicationExit = async (operations: ApplicationExitOperations, manageTasks: boolean) => {
  try {
    if (manageTasks) {
      await operations.prepareUserExit();
    }
    if (operations.finalizeBeforeClose && !(await operations.finalizeBeforeClose())) {
      if (manageTasks) {
        await operations.abortUserExit().catch(() => undefined);
      }
      operations.onCancel();
      return;
    }
    const confirmed = await operations.confirmCloseWindow();
    if (!confirmed) {
      throw new Error('No pending application exit request');
    }
  } catch (error) {
    if (manageTasks) {
      await operations.abortUserExit().catch(() => undefined);
    }
    await operations.cancelApplicationExit().catch(() => undefined);
    throw error;
  }
};

export const coordinateApplicationExit = async (operations: ApplicationExitOperations) => {
  if (!(await operations.confirmDirtyEditors())) {
    operations.onCancel();
    return;
  }
  const manageTasks = operations.shouldManageTasks();
  if (!manageTasks) {
    await finishApplicationExit(operations, false);
    return;
  }
  const activeTaskCount = await operations.getActiveTaskCount();
  if (activeTaskCount > 0) {
    operations.requestConfirmation({
      activeTaskCount,
      onCancel: operations.onCancel,
      onConfirm: () => finishApplicationExit(operations, true),
    });
    return;
  }
  await finishApplicationExit(operations, true);
};
