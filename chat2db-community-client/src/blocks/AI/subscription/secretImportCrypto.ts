import type { AiSecretImportEncryptedEnvelope } from '@/typings/aiSubscription';

const encoder = new TextEncoder();

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const value of bytes) binary += String.fromCharCode(value);
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

export async function encryptLegacyModelConfig(params: {
  attemptId: string;
  itemId: string;
  publicKeySpkiBase64: string;
  expiresAtEpochMs: number;
  payload: Record<string, unknown>;
  confirmDefault: boolean;
}): Promise<AiSecretImportEncryptedEnvelope> {
  if (!globalThis.crypto?.subtle) throw new Error('WEB_CRYPTO_UNAVAILABLE');
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const nonceBase64 = bytesToBase64(nonce);
  const aad = encoder.encode(
    `1|${params.attemptId}|${params.itemId}|${nonceBase64}|${params.expiresAtEpochMs}`,
  );
  const plaintext = encoder.encode(JSON.stringify(params.payload));
  const publicKeyBytes = base64ToBytes(params.publicKeySpkiBase64);
  const aesKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt']);
  try {
    const publicKey = await crypto.subtle.importKey(
      'spki',
      publicKeyBytes,
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      false,
      ['encrypt'],
    );
    const rawAesKey = new Uint8Array(await crypto.subtle.exportKey('raw', aesKey));
    try {
      const [ciphertext, wrappedKey] = await Promise.all([
        crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 }, aesKey, plaintext),
        crypto.subtle.encrypt({ name: 'RSA-OAEP' }, publicKey, rawAesKey),
      ]);
      return {
        schemaVersion: 1,
        attemptId: params.attemptId,
        itemId: params.itemId,
        nonceBase64,
        expiresAtEpochMs: params.expiresAtEpochMs,
        wrappedKeyBase64: bytesToBase64(new Uint8Array(wrappedKey)),
        ciphertextBase64: bytesToBase64(new Uint8Array(ciphertext)),
        confirmDefault: params.confirmDefault,
      };
    } finally {
      rawAesKey.fill(0);
    }
  } finally {
    plaintext.fill(0);
    publicKeyBytes.fill(0);
    nonce.fill(0);
    aad.fill(0);
  }
}
