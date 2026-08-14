import { existsSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import sharp from 'sharp';
import { z } from 'zod';
import type Database from 'better-sqlite3';
import type { ClipInput, JobHandle, JobProgress } from '../types';
import type { DownloadService } from './DownloadService';
import type { FFmpegService, MediaAnalysis } from './FFmpegService';
import type { OllamaService } from './OllamaService';

interface Cue { start: number; end: number; text: string }
interface Candidate { start: number; end: number; score: number; transcript: string }

const responseSchema = z.object({ clips: z.array(z.object({
  start: z.number().nonnegative(), end: z.number().positive(), score: z.number().min(1).max(10),
  title: z.string().min(1).max(70), hook: z.string(), hashtags: z.array(z.string()).min(1).max(8), reason: z.string()
})) });

function timestamp(value: string): number {
  const parts = value.replace(',', '.').split(':').map(Number);
  if (parts.length === 3) return (parts[0] ?? 0) * 3600 + (parts[1] ?? 0) * 60 + (parts[2] ?? 0);
  return (parts[0] ?? 0) * 60 + (parts[1] ?? 0);
}

function parseVtt(value: string): Cue[] {
  const normalized = value.replaceAll('\r', '');
  const blocks = normalized.split(/\n\n+/);
  const cues: Cue[] = [];
  for (const block of blocks) {
    const lines = block.split('\n').filter(Boolean);
    const timingIndex = lines.findIndex((line) => line.includes('-->'));
    if (timingIndex < 0) continue;
    const match = /([\d:.]+)\s+-->\s+([\d:.]+)/.exec(lines[timingIndex] ?? '');
    if (!match?.[1] || !match[2]) continue;
    const text = lines.slice(timingIndex + 1).join(' ').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
    if (text) cues.push({ start: timestamp(match[1]), end: timestamp(match[2]), text });
  }
  return cues;
}

function srtTime(seconds: number): string {
  const safe = Math.max(0, seconds);
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const secs = Math.floor(safe % 60);
  const ms = Math.floor((safe % 1) * 1000);
  return [hours, minutes, secs].map((part) => String(part).padStart(2, '0')).join(':') + `,${String(ms).padStart(3, '0')}`;
}

function createSrt(cues: Cue[], start: number, end: number): string {
  const output: string[] = [];
  let index = 1;
  for (const cue of cues.filter((item) => item.end > start && item.start < end)) {
    const words = cue.text.split(/\s+/);
    const chunks = Array.from({ length: Math.ceil(words.length / 5) }, (_, part) => words.slice(part * 5, part * 5 + 5).join(' '));
    const duration = Math.max(0.3, Math.min(end, cue.end) - Math.max(start, cue.start));
    chunks.forEach((text, part) => {
      const from = Math.max(start, cue.start) - start + duration * part / chunks.length;
      const to = Math.max(start, cue.start) - start + duration * (part + 1) / chunks.length;
      output.push(String(index++), `${srtTime(from)} --> ${srtTime(to)}`, text, '');
    });
  }
  return output.join('\n');
}

function candidates(duration: number, maxLength: number, cues: Cue[], analysis: MediaAnalysis): Candidate[] {
  const boundaries = [0, ...analysis.scenes, ...analysis.silences.flatMap((silence) => [silence.start, silence.end]), duration]
    .filter((value) => value >= 0 && value <= duration).sort((left, right) => left - right);
  const starts = [...new Set(boundaries.map((value) => Math.round(value * 10) / 10))];
  const keywords = /\b(wait|oh my god|wow|look|guys|literally|insane|crazy|never|best|worst)\b|صبر|وای|نگاه|باور|عجیب|بهترین|بدترین/gi;
  const result: Candidate[] = [];
  for (const start of starts) {
    const endBoundary = starts.find((value) => value > start + 10) ?? Math.min(duration, start + maxLength);
    const end = Math.min(duration, start + maxLength, endBoundary);
    if (end - start < 8) continue;
    const related = cues.filter((cue) => cue.end > start && cue.start < end);
    const transcript = related.map((cue) => cue.text).join(' ');
    const keywordScore = Math.min(1, (transcript.match(keywords)?.length ?? 0) / 4);
    const speechRate = Math.min(1, transcript.split(/\s+/).filter(Boolean).length / Math.max(1, end - start) / 3);
    const sceneDensity = Math.min(1, analysis.scenes.filter((time) => time >= start && time <= end).length / 4);
    const peaks = analysis.audioPeaks.filter((peak) => peak.time >= start && peak.time <= end);
    const audio = peaks.length ? Math.max(...peaks.map((peak) => peak.score)) : 0.3;
    result.push({ start, end, transcript, score: audio * 0.35 + sceneDensity * 0.25 + speechRate * 0.2 + keywordScore * 0.2 });
  }
  if (!result.length) {
    const step = Math.max(10, duration / 12);
    for (let start = 0; start < duration - 8; start += step) result.push({ start, end: Math.min(duration, start + maxLength), transcript: '', score: 0.3 });
  }
  return result.sort((left, right) => right.score - left.score);
}

function snap(value: number, silences: MediaAnalysis['silences']): number {
  const points = silences.flatMap((item) => [item.start, item.end]).filter((point) => Math.abs(point - value) <= 2);
  return points.sort((left, right) => Math.abs(left - value) - Math.abs(right - value))[0] ?? value;
}

function escapeSvg(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}

export class ClipperEngine {
  constructor(
    private readonly db: Database.Database,
    private readonly downloads: DownloadService,
    private readonly ffmpeg: FFmpegService,
    private readonly ollama: OllamaService,
    private readonly temp: string,
    private readonly musicPath: string | undefined,
    private readonly fontsDirectory: string,
    private readonly emit: (channel: string, value: unknown) => void
  ) {}

  start(input: ClipInput): JobHandle {
    const jobId = crypto.randomUUID();
    void this.process(jobId, input);
    return { jobId };
  }

  private progress(jobId: string, phase: string, percent: number, message?: string): void {
    const value: JobProgress = { jobId, phase, percent, message };
    this.emit('job:progress', value);
  }

  private async process(jobId: string, input: ClipInput): Promise<void> {
    try {
      const directory = path.join(this.temp, jobId);
      await mkdir(directory, { recursive: true });
      this.progress(jobId, 'download', 2);
      let source = input.localPath;
      if (!source && input.url) source = await this.downloads.download(input.url, directory, 'bestvideo[height<=1080]+bestaudio/best', (percent) => this.progress(jobId, 'download', percent));
      if (!source) throw new Error('Choose a URL or local video');
      const probe = await this.ffmpeg.probe(source);
      this.progress(jobId, 'audio-scenes', 20, 'Analyzing audio, silence, and scene changes');
      const analysis = await this.ffmpeg.analyze(source);
      this.progress(jobId, 'transcript', 32, 'Loading captions');
      let cues: Cue[] = [];
      if (input.url) {
        try {
          const captionsPath = await this.downloads.captions(input.url, directory);
          if (captionsPath) cues = parseVtt(await readFile(captionsPath, 'utf8'));
        } catch { cues = []; }
      }
      const ranked = candidates(probe.duration, input.maxLength, cues, analysis).slice(0, input.count * 3);
      const prompt = `You are an expert short-form video editor. Given candidate segments, pick EXACTLY ${String(input.count)} most viral clips for YouTube Shorts. Prioritize emotional spikes, surprise, controversy, punchlines, game-winning moments, revelations, calls to action, and funny moments. Category: ${input.category}. Maximum duration ${String(input.maxLength)} seconds. Candidates: ${JSON.stringify(ranked)}. For each clip return start, end, score 1-10, title no more than 70 characters, hook, 3-5 hashtags, and reason. Return ONLY JSON {"clips":[...]}; no prose or markdown.`;
      this.progress(jobId, 'llm', 45);
      let parsed: z.infer<typeof responseSchema> | undefined;
      for (let attempt = 0; attempt < 3 && !parsed; attempt++) {
        try {
          const candidate = responseSchema.parse(JSON.parse(await this.ollama.generate(input.model, prompt)));
          if (candidate.clips.length !== input.count) throw new Error(`Model returned ${String(candidate.clips.length)} clips instead of ${String(input.count)}`);
          parsed = candidate;
        } catch (error: unknown) { if (attempt === 2) throw error; }
      }
      if (!parsed) throw new Error('Model returned invalid clips');
      for (const [index, suggested] of parsed.clips.entries()) {
        const start = Math.max(0, snap(suggested.start, analysis.silences));
        const end = Math.min(probe.duration, start + input.maxLength, snap(suggested.end, analysis.silences));
        if (end <= start + 1) continue;
        this.progress(jobId, 'rendering', 50 + Math.round(index / parsed.clips.length * 45), `Rendering clip ${String(index + 1)}`);
        const video = path.join(directory, `final_clip_${String(index + 1)}.mp4`);
        const rawThumb = path.join(directory, `thumb_raw_${String(index + 1)}.jpg`);
        const thumb = path.join(directory, `thumb_${String(index + 1)}.jpg`);
        let captionsPath: string | undefined;
        if (input.captions && cues.length) {
          captionsPath = path.join(directory, `clip_${String(index + 1)}.srt`);
          await writeFile(captionsPath, createSrt(cues, start, end), 'utf8');
        }
        await this.ffmpeg.render(source, start, end, video, {
          aspect: input.aspect, captionsPath, fontsDirectory:this.fontsDirectory, smartZoom: input.smartZoom, blurBackground: input.blurBackground,
          musicPath: input.music && this.musicPath && existsSync(this.musicPath) ? this.musicPath : undefined, musicVolume: 0.12
        });
        const scene = analysis.scenes.filter((time) => time >= start && time <= end).sort((left, right) => Math.abs(left - (start + (end - start) * 0.3)) - Math.abs(right - (start + (end - start) * 0.3)))[0];
        await this.ffmpeg.frame(source, scene ?? start + (end - start) * 0.3, rawThumb);
        const title = escapeSvg(suggested.title);
        const overlay = Buffer.from(`<svg width="1080" height="1920"><defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="55%" stop-color="black" stop-opacity="0"/><stop offset="100%" stop-color="black" stop-opacity="0.9"/></linearGradient></defs><rect width="1080" height="1920" fill="url(#g)"/><rect x="42" y="42" width="210" height="64" rx="18" fill="#ff0000"/><text x="147" y="85" text-anchor="middle" fill="white" font-family="Arial" font-size="30" font-weight="bold">SHORTS</text><foreignObject x="70" y="1420" width="940" height="390"><div xmlns="http://www.w3.org/1999/xhtml" style="font:700 68px Arial;color:white;text-align:center;text-shadow:0 4px 8px black;line-height:1.12">${title}</div></foreignObject></svg>`);
        await sharp(rawThumb).resize(1080, 1920, { fit: 'cover' }).composite([{ input: overlay }]).jpeg({ quality: 90 }).toFile(thumb);
        const row = this.db.prepare('INSERT INTO clips(source_path,start_time,end_time,score,suggested_title,hook,hashtags,reason,local_path,thumbnail_path) VALUES(?,?,?,?,?,?,?,?,?,?)')
          .run(source, start, end, suggested.score, suggested.title, suggested.hook, JSON.stringify(suggested.hashtags), suggested.reason, video, thumb);
        this.emit('clip:ready', { id: Number(row.lastInsertRowid), ...suggested, start, end, localPath: video, thumbnailPath: thumb });
      }
      this.emit('job:done', { jobId });
    } catch (error: unknown) {
      this.emit('job:done', { jobId, error: error instanceof Error ? error.message : String(error) });
    }
  }

  list(): unknown[] { return this.db.prepare('SELECT * FROM clips ORDER BY created_at DESC').all(); }
  get(id: number): { id: number; suggested_title: string; hashtags: string; local_path: string; thumbnail_path: string; status: string } | undefined {
    return this.db.prepare('SELECT id,suggested_title,hashtags,local_path,thumbnail_path,status FROM clips WHERE id=?').get(id) as { id: number; suggested_title: string; hashtags: string; local_path: string; thumbnail_path: string; status: string } | undefined;
  }
  update(id: number, patch: { status?: string; suggested_title?: string; hashtags?: string }): void {
    const fields = Object.entries(patch);
    if (fields.length) this.db.prepare(`UPDATE clips SET ${fields.map(([key]) => `${key}=?`).join(',')} WHERE id=?`).run(...fields.map(([, value]) => value), id);
  }
}
