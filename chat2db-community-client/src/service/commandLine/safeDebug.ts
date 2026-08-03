export interface SafeIpcDebugMetadata {
  requestId: string;
  method: string;
  route: string;
  payloadSize: number;
}

const byteLength = (value: unknown): number => {
  try {
    const serialized = JSON.stringify(value) || '';
    return new TextEncoder().encode(serialized).length;
  } catch {
    return -1;
  }
};

export const createSafeIpcDebugMetadata = (
  requestId: string,
  method: string,
  route: string,
  payload: unknown,
): SafeIpcDebugMetadata => ({
  requestId,
  method,
  route,
  payloadSize: byteLength(payload),
});

