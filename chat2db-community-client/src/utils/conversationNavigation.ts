export interface PendingConversationTarget {
  sessionId: string;
  messageId?: string;
}

let pendingTarget: PendingConversationTarget | undefined;

export function setPendingConversationTarget(target: PendingConversationTarget) {
  pendingTarget = target;
}

export function takePendingConversationTarget(sessionId: string) {
  if (pendingTarget?.sessionId !== sessionId) return undefined;
  const target = pendingTarget;
  pendingTarget = undefined;
  return target;
}
