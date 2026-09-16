import type { commandLineRequest } from '../../commandLine/commandLine';
import { PI_ENDPOINT, type PiResponse, type PiTransport } from '../contract';

export function createDesktopPiTransport(request: typeof commandLineRequest): PiTransport {
  return {
    invoke: (payload, signal) => request<PiResponse>({
      requestUrl: PI_ENDPOINT, method: 'post', message: payload,
    }, { errorLevel: false, permissionError: false, timeout: false, rawResponse: true, restParams: { signal } }),
  };
}
