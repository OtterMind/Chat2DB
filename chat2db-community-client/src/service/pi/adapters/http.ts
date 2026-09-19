import { PI_ENDPOINT, type PiTransport } from '../contract';
import { PiRequestError } from '../client';

export function createHttpPiTransport(headers: () => Record<string, string>, request = fetch): PiTransport {
  return {
    async invoke(payload, signal) {
      const response = await request(PI_ENDPOINT, {
        method: 'POST', credentials: 'include', signal,
        headers: { ...headers(), 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!response.ok) throw new PiRequestError(`http.${response.status}`, response.statusText || 'Pi HTTP request failed');
      return response.json();
    },
  };
}
