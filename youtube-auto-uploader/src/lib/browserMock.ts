import type { AppSettings, AuthState, Channel, JobHandle, VideoMetadata } from '../../electron/types';
import type { ClipRow } from '../global';

const settings: AppSettings = { language: 'en', theme: 'dark', defaultPrivacy: 'unlisted', downloadConcurrency: 2, uploadConcurrency: 1, keepDownloads: false, autoStartMonitor: true, minimizeToTray: true, ollamaEndpoint: 'http://127.0.0.1:11434', defaultModel: 'qwen2.5:7b-instruct-q4_0', acceptedCopyright: true, onboardingComplete: true, devtools: false };
const auth: AuthState = { authenticated: true, hasCredentials: true, account: { email: 'preview@example.com', name: 'Preview Creator', channelName: 'Preview Channel' } };
const jobs: Array<Record<string, unknown>> = [
  { id: 1, source_title: 'Product launch highlight', status: 'success', upload_type: 'clipper', created_at: new Date().toISOString(), uploaded_url: 'https://youtube.com' },
  { id: 2, source_title: 'Weekly tutorial', status: 'pending', upload_type: 'auto_sync', created_at: new Date().toISOString() }
];
const channels: Channel[] = [{ id: 1, youtube_channel_id: 'UCpreview', channel_name: 'Example Source', channel_handle: '@example', thumbnail_url: null, interval_hours: 6, auto_upload: 0, privacy: 'unlisted', is_active: 1, last_checked_at: new Date().toISOString(), last_error: null }];
const clips: ClipRow[] = [];
const handle = (): JobHandle => ({ jobId: crypto.randomUUID() });

export function installBrowserMock(): void {
  if (window.api || !import.meta.env.DEV) return;
  window.api = {
    auth: { getState: async () => auth, startLogin: async () => auth, logout: async () => undefined, saveClientCredentials: async () => undefined, hasClientCredentials: async () => true },
    channels: { list: async () => channels, add: async () => 2, update: async () => undefined, remove: async () => undefined, checkNow: async () => undefined },
    video: {
      metadata: async (url: string): Promise<VideoMetadata> => ({ id: 'preview', title: 'Preview video', description: 'Preview metadata', thumbnail: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg', channel: 'Example channel', duration: 212, viewCount: 125000, url }),
      playlist: async () => []
    },
    upload: { single: async () => handle(), batch: async (inputs) => inputs.map(handle), list: async (filter) => filter ? jobs.filter((job) => job.status === filter) : jobs, exportHistory: async () => null, cancel: async () => undefined, approve: async () => handle(), reject: async () => undefined, retry: async () => handle(), pause: async () => undefined, resume: async () => undefined },
    clipper: { start: async () => handle(), clips: async () => clips, update: async () => undefined, export: async () => null, upload: async () => handle() },
    quota: { state: async () => ({ used: 3200, remaining: 6800, limit: 10000 }) },
    ollama: { status: async () => ({ running: true, models: ['qwen2.5:7b-instruct-q4_0'] }), pull: async () => undefined },
    settings: { getAll: async () => settings, set: async (patch) => Object.assign(settings, patch), pickDir: async () => null, pickFile: async () => null, pickImage: async () => null },
    openExternal: async () => undefined,
    on: () => () => undefined
  };
}
