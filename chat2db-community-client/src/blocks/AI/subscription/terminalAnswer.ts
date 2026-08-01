/**
 * Returns only the suffix still missing from the streamed answer.
 *
 * JCEF schedules Java-to-JavaScript pushes independently. The terminal event
 * therefore carries the complete answer as a recovery envelope, while this
 * helper keeps rendering idempotent when every delta arrived normally.
 */
export function resolveTerminalAnswerFallback(currentText: string, finalAnswer?: string | null): string {
  if (!finalAnswer || currentText === finalAnswer) {
    return '';
  }
  if (!currentText) {
    return finalAnswer;
  }
  return finalAnswer.startsWith(currentText) ? finalAnswer.slice(currentText.length) : '';
}
