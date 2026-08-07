export interface ApplicationExitConfirmation {
  activeTaskCount: number;
  onCancel: () => void;
  onConfirm: () => Promise<void>;
}

interface ApplicationExitOperations {
  getActiveTaskCount: () => Promise<number>;
  prepareUserExit: () => Promise<void>;
  confirmCloseWindow: () => Promise<boolean>;
  requestConfirmation: (confirmation: ApplicationExitConfirmation) => void;
  onCancel: () => void;
}

const finishApplicationExit = async (operations: ApplicationExitOperations) => {
  await operations.prepareUserExit();
  const confirmed = await operations.confirmCloseWindow();
  if (!confirmed) {
    throw new Error('No pending application exit request');
  }
};

export const coordinateApplicationExit = async (operations: ApplicationExitOperations) => {
  const activeTaskCount = await operations.getActiveTaskCount();
  if (activeTaskCount > 0) {
    operations.requestConfirmation({
      activeTaskCount,
      onCancel: operations.onCancel,
      onConfirm: () => finishApplicationExit(operations),
    });
    return;
  }
  await finishApplicationExit(operations);
};
