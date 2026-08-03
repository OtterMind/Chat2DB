/**
 * Dedicated JCEF transport for encrypted legacy API-key import.
 *
 * This deliberately bypasses the generic command-line request store, interceptors,
 * console tracing and toast/notification paths. The encrypted envelope exists only
 * on this call stack and inside the early Java secret-import interceptor.
 */
export async function secretImportRequest<R>(requestUrl: string, message: unknown): Promise<R> {
  if (typeof window.javaQuery !== 'function') {
    throw new Error('SECRET_IMPORT_BRIDGE_UNAVAILABLE');
  }
  const uuid = globalThis.crypto?.randomUUID?.() || `secret-import-${Date.now()}`;
  const request = JSON.stringify({
    actionType: 'execute',
    requestUrl,
    method: 'post',
    uuid,
    message,
  });

  return new Promise<R>((resolve, reject) => {
    window.javaQuery({
      request,
      onSuccess: (raw) => {
        try {
          const parsed = JSON.parse(raw);
          if (parsed?.uuid !== uuid || parsed?.message?.success !== true) {
            reject(new Error('SECRET_IMPORT_INVALID_RESPONSE'));
            return;
          }
          resolve(parsed.message.data as R);
        } catch {
          reject(new Error('SECRET_IMPORT_INVALID_RESPONSE'));
        }
      },
      // Never surface native error detail because some JCEF implementations echo input.
      onFailure: () => reject(new Error('SECRET_IMPORT_NATIVE_FAILURE')),
    });
  });
}
