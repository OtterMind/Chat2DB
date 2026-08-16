const MAX_PERSISTED_TERMINALS = 50;
interface PersistentTerminalState {
  buffer: string;
  lastRenderedSequence?: number;
}

const terminalStates = new Map<string, PersistentTerminalState>();

function touchTerminalState(sessionId: string, state: PersistentTerminalState) {
  terminalStates.delete(sessionId);
  terminalStates.set(sessionId, state);
  while (terminalStates.size > MAX_PERSISTED_TERMINALS) {
    terminalStates.delete(terminalStates.keys().next().value!);
  }
}

export function getPersistentTerminalBuffer(sessionId: string) {
  const state = terminalStates.get(sessionId);
  if (state) {
    touchTerminalState(sessionId, state);
  }
  return state?.buffer;
}

export function setPersistentTerminalBuffer(sessionId: string, buffer: string) {
  touchTerminalState(sessionId, {
    ...terminalStates.get(sessionId),
    buffer,
  });
}

export function getLastRenderedTerminalSequence(sessionId: string) {
  return terminalStates.get(sessionId)?.lastRenderedSequence;
}

export function setLastRenderedTerminalSequence(sessionId: string, sequence: number) {
  const currentState = terminalStates.get(sessionId);
  if (currentState?.lastRenderedSequence !== undefined && currentState.lastRenderedSequence >= sequence) {
    return;
  }
  touchTerminalState(sessionId, {
    buffer: currentState?.buffer || '',
    lastRenderedSequence: sequence,
  });
}

export function clearPersistentTerminalBuffer(sessionId: string) {
  terminalStates.delete(sessionId);
}
