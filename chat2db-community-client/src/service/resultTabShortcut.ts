export type CloseActiveResultTabHandlerResult = 'closed' | 'pass-through' | 'inactive';
export type CloseActiveResultTabHandler = () => CloseActiveResultTabHandlerResult;

const closeActiveResultTabHandlers: CloseActiveResultTabHandler[] = [];

export function registerCloseActiveResultTabHandler(handler: CloseActiveResultTabHandler) {
  closeActiveResultTabHandlers.push(handler);
  return () => {
    const index = closeActiveResultTabHandlers.lastIndexOf(handler);
    if (index >= 0) {
      closeActiveResultTabHandlers.splice(index, 1);
    }
  };
}

export function requestCloseActiveResultTab() {
  for (let index = closeActiveResultTabHandlers.length - 1; index >= 0; index -= 1) {
    const result = closeActiveResultTabHandlers[index]();
    if (result === 'inactive') {
      continue;
    }
    return result === 'closed';
  }
  return false;
}
