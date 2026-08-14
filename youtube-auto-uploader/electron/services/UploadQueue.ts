import { EventEmitter } from 'node:events';
import { mkdir, rm } from 'node:fs/promises';
import path from 'node:path';
import type Database from 'better-sqlite3';
import type { DownloadService } from './DownloadService';
import type { YouTubeService } from './YouTubeService';
import type { JobHandle, JobProgress, UploadInput } from '../types';
import { isRetryable, retry } from '../utils/retry';

interface QueuedJob {
  id: string;
  input: UploadInput;
  priority: number;
  dbId: number;
  type: 'single' | 'batch' | 'auto_sync' | 'clipper';
  cancelled: boolean;
}

interface StoredJob {
  id: number;
  source_url: string | null;
  source_title: string | null;
  privacy: 'public' | 'unlisted' | 'private';
  upload_type: QueuedJob['type'];
  payload_json: string | null;
}

export class UploadQueue extends EventEmitter {
  private readonly jobs: QueuedJob[] = [];
  private active = false;
  private paused = false;

  constructor(
    readonly db: Database.Database,
    private readonly downloads: DownloadService,
    private readonly youtube: YouTubeService,
    private readonly temp: string,
    private readonly keepDownloads: () => boolean
  ) {
    super();
    this.db.prepare("UPDATE synced_videos SET status='queued', updated_at=datetime('now') WHERE status IN ('downloading','uploading')").run();
    const interrupted = this.db.prepare("SELECT id,source_url,source_title,privacy,upload_type,payload_json FROM synced_videos WHERE status='queued' ORDER BY created_at").all() as StoredJob[];
    for (const stored of interrupted) this.restore(stored);
    queueMicrotask(() => { void this.pump(); });
  }

  enqueue(input: UploadInput, type: QueuedJob['type'] = 'single', channelId: number | null = null): JobHandle {
    if (!input.url && !input.localPath) throw new Error('A source URL or local file is required');
    const id = crypto.randomUUID();
    const sourceId = input.localPath ? `local:${id}` : id;
    const result = this.db.prepare("INSERT INTO synced_videos(channel_id,source_video_id,source_url,source_title,upload_type,privacy,status,payload_json) VALUES(?,?,?,?,?,?,'queued',?)")
      .run(channelId, sourceId, input.url ?? null, input.sourceTitle ?? null, type, input.privacy ?? 'unlisted', JSON.stringify(input));
    this.push({ id, input, priority: this.priority(type), dbId: Number(result.lastInsertRowid), type, cancelled: false });
    return { jobId: id };
  }

  approve(databaseId: number): JobHandle {
    const stored = this.db.prepare("SELECT id,source_url,source_title,privacy,upload_type,payload_json FROM synced_videos WHERE id=? AND status='pending'").get(databaseId) as StoredJob | undefined;
    if (!stored) throw new Error('Pending upload not found');
    this.db.prepare("UPDATE synced_videos SET status='queued',updated_at=datetime('now') WHERE id=?").run(databaseId);
    return this.restore(stored);
  }

  reject(databaseId: number): void {
    this.db.prepare("UPDATE synced_videos SET status='skipped',updated_at=datetime('now') WHERE id=? AND status='pending'").run(databaseId);
  }

  retryJob(databaseId: number): JobHandle {
    const stored = this.db.prepare("SELECT id,source_url,source_title,privacy,upload_type,payload_json FROM synced_videos WHERE id=? AND status='failed'").get(databaseId) as StoredJob | undefined;
    if (!stored) throw new Error('Failed upload not found');
    this.db.prepare("UPDATE synced_videos SET status='queued',error_message=NULL,updated_at=datetime('now') WHERE id=?").run(databaseId);
    return this.restore(stored);
  }

  pause(): void { this.paused = true; }
  resume(): void { this.paused = false; void this.pump(); }
  cancel(jobId: string): void {
    const job = this.jobs.find((item) => item.id === jobId);
    if (job) job.cancelled = true;
  }

  private restore(stored: StoredJob): JobHandle {
    let input: UploadInput;
    try {
      input = stored.payload_json ? JSON.parse(stored.payload_json) as UploadInput : {
        url: stored.source_url ?? undefined,
        sourceTitle: stored.source_title ?? undefined,
        privacy: stored.privacy
      };
    } catch {
      input = { url: stored.source_url ?? undefined, sourceTitle: stored.source_title ?? undefined, privacy: stored.privacy };
    }
    if (!input.url && !input.localPath) throw new Error(`Stored job ${String(stored.id)} has no source`);
    const handle = { jobId: crypto.randomUUID() };
    this.push({ id: handle.jobId, input, priority: this.priority(stored.upload_type), dbId: stored.id, type: stored.upload_type, cancelled: false });
    return handle;
  }

  private push(job: QueuedJob): void {
    this.jobs.push(job);
    this.jobs.sort((left, right) => left.priority - right.priority);
    void this.pump();
  }

  private priority(type: QueuedJob['type']): number {
    return type === 'single' || type === 'clipper' ? 0 : type === 'batch' ? 1 : 2;
  }

  private progress(value: JobProgress): void { this.emit('progress', value); }

  private async pump(): Promise<void> {
    if (this.active || this.paused) return;
    const job = this.jobs.shift();
    if (!job) return;
    this.active = true;
    const directory = path.join(this.temp, job.id);
    let downloaded = false;
    try {
      await mkdir(directory, { recursive: true });
      let file = job.input.localPath;
      let sourceTitle = job.input.sourceTitle ?? job.input.title ?? 'Uploaded video';
      let description = job.input.description ?? '';
      if (!file) {
        if (!job.input.url) throw new Error('Source URL is missing');
        this.db.prepare("UPDATE synced_videos SET status='downloading',updated_at=datetime('now') WHERE id=?").run(job.dbId);
        const metadata = await retry(() => this.downloads.metadata(job.input.url as string), { attempts: 3, retryable: isRetryable });
        sourceTitle = metadata.title;
        description ||= metadata.description;
        this.db.prepare('UPDATE synced_videos SET source_video_id=?,source_title=?,source_channel_name=? WHERE id=?')
          .run(metadata.id, metadata.title, metadata.channel, job.dbId);
        file = await retry(() => this.downloads.download(
          job.input.url as string,
          directory,
          job.input.quality ?? 'bestvideo[height<=1080]+bestaudio/best',
          (percent, line) => this.progress({ jobId: job.id, phase: 'downloading', percent, message: line.trim() })
        ), { attempts: 3, retryable: isRetryable });
        downloaded = true;
      }
      if (job.cancelled) throw new Error('Cancelled');
      this.db.prepare("UPDATE synced_videos SET status='uploading',downloaded_path=?,updated_at=datetime('now') WHERE id=?").run(file, job.dbId);
      const title = (job.input.title ?? sourceTitle).replace('{original_title}', sourceTitle);
      const result = await retry(() => this.youtube.upload(file as string, { ...job.input, title, description }, (percent) => {
        this.progress({ jobId: job.id, phase: 'uploading', percent });
      }), { attempts: 3, retryable: isRetryable });
      this.db.prepare("UPDATE synced_videos SET status='success',uploaded_video_id=?,uploaded_url=?,updated_at=datetime('now') WHERE id=?")
        .run(result.id, result.url, job.dbId);
      this.db.prepare("INSERT INTO upload_history(source_url,source_title,uploaded_id,uploaded_url,upload_type,status) VALUES(?,?,?,?,?,'success')")
        .run(job.input.url ?? null, sourceTitle, result.id, result.url, job.type);
      if (job.input.clipId) this.db.prepare("UPDATE clips SET status='uploaded',uploaded_video_id=?,uploaded_url=? WHERE id=?").run(result.id,result.url,job.input.clipId);
      this.emit('done', { jobId: job.id, ...result });
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : String(error);
      const status = job.cancelled || message === 'Cancelled' ? 'cancelled' : 'failed';
      this.db.prepare("UPDATE synced_videos SET status=?,error_message=?,attempts=attempts+1,updated_at=datetime('now') WHERE id=?")
        .run(status, message, job.dbId);
      if (/quotaExceeded/i.test(message)) this.paused = true;
      this.emit('done', { jobId: job.id, error: message });
    } finally {
      if (downloaded && !this.keepDownloads()) await rm(directory, { recursive: true, force: true });
      this.active = false;
      void this.pump();
    }
  }

  list(status?: string): unknown[] {
    return status
      ? this.db.prepare('SELECT * FROM synced_videos WHERE status=? ORDER BY created_at DESC').all(status)
      : this.db.prepare('SELECT * FROM synced_videos ORDER BY created_at DESC').all();
  }
}
