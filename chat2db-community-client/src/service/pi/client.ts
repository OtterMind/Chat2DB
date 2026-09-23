import { v4 as uuid } from 'uuid';
import { PI_PROTOCOL_VERSION, type PiCallOptions, type PiOperation, type PiOperations, type PiRequest, type PiResponse, type PiTransport } from './contract';

export class PiRequestError extends Error {
  constructor(public readonly errorCode: string, public readonly errorMessage: string) {
    super(errorMessage);
    this.name = 'PiRequestError';
  }
}

export function createPiClient(transport: PiTransport) {
  async function invoke<K extends PiOperation>(operation: K, payload: PiOperations[K]['input'],
    options: PiCallOptions = {}): Promise<PiOperations[K]['output']> {
    options.signal?.throwIfAborted();
    const request: PiRequest = { protocolVersion: PI_PROTOCOL_VERSION, requestId: uuid(),
      operation, payload: payload ?? {} };
    const controller = new AbortController();
    const abort = () => controller.abort(options.signal?.reason);
    options.signal?.addEventListener('abort', abort, { once: true });
    const timeout = options.timeoutMs ?? 300_000;
    const timer = timeout > 0 ? setTimeout(() => controller.abort(
      new PiRequestError('pi.timeout', 'Pi request timed out'),
    ), timeout) : undefined;
    let rejectAbort: () => void = () => {};
    try {
      const aborted = new Promise<never>((_, reject) => {
        rejectAbort = () => reject(controller.signal.reason);
        controller.signal.addEventListener('abort', rejectAbort, { once: true });
      });
      const response = await Promise.race([transport.invoke(request, controller.signal), aborted]);
      if (!response || response.protocolVersion !== PI_PROTOCOL_VERSION || response.requestId !== request.requestId
        || typeof response.success !== 'boolean') {
        throw new PiRequestError('pi.invalidResponse', 'Invalid Pi response');
      }
      if (!response.success) throw new PiRequestError(response.errorCode || 'pi.failed', response.errorMessage || 'Pi request failed');
      return response.data as PiOperations[K]['output'];
    } catch (error) {
      if (controller.signal.aborted) throw controller.signal.reason;
      if (error instanceof Error) throw error;
      const detail = error as Partial<Pick<PiResponse, 'errorCode' | 'errorMessage'>> | null;
      throw new PiRequestError(detail?.errorCode || 'pi.transport', detail?.errorMessage || String(error));
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', abort);
      controller.signal.removeEventListener('abort', rejectAbort);
    }
  }
  const bind = <K extends PiOperation>(operation: K) =>
    (payload: PiOperations[K]['input'], options?: PiCallOptions) => invoke(operation, payload, options);
  return {
    skills: { list: bind('skills.list') },
    runtime: { list: bind('runtime.list'), check: bind('runtime.check'),
      enable: bind('runtime.enable'), disable: bind('runtime.disable') },
    bash: { check: bind('bash.check'), enable: bind('bash.enable'), disable: bind('bash.disable') },
    tools: { list: bind('tools.list'), setEnabled: bind('tools.setEnabled') },
    workspace: { get: bind('workspace.get'), set: bind('workspace.set'),
      selectDirectory: bind('workspace.selectDirectory') },
    sessions: { list: bind('sessions.list'), create: bind('sessions.create'), get: bind('sessions.get'),
      rename: bind('sessions.rename'), delete: bind('sessions.delete') },
    runs: { start: bind('runs.start'), cancel: bind('runs.cancel') },
    events: { list: bind('events.list') },
    approvals: { list: bind('approvals.list'), decide: bind('approvals.decide') },
    questions: { list: bind('questions.list'), answer: bind('questions.answer') },
    outputs: { read: bind('outputs.read'), search: bind('outputs.search'), save: bind('outputs.save') },
    attachments: { parseLocal: bind('attachments.parseLocal') },
    models: { prepare: bind('models.prepare') },
  };
}

export type PiClient = ReturnType<typeof createPiClient>;
