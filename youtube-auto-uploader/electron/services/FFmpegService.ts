import { spawn } from 'node:child_process';
import path from 'node:path';
import { binaryPath } from '../bin';

export interface MediaAnalysis {
  scenes: number[];
  silences: Array<{ start: number; end: number }>;
  audioPeaks: Array<{ time: number; score: number }>;
}

export interface RenderOptions {
  aspect: string;
  captionsPath?: string;
  fontsDirectory?: string;
  smartZoom: boolean;
  blurBackground: boolean;
  musicPath?: string;
  musicVolume?: number;
}

function subtitlePath(file: string): string {
  return file.replaceAll('\\', '/').replace(/^([A-Za-z]):/, '$1\\:').replaceAll("'", "\\'");
}

export class FFmpegService {
  async run(args: string[], onLine?: (line: string) => void): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      const child = spawn(binaryPath('ffmpeg'), ['-hide_banner', '-y', ...args], { windowsHide: true });
      child.stderr.setEncoding('utf8');
      child.stderr.on('data', (value: string) => onLine?.(value));
      child.once('error', reject);
      child.once('close', (code) => code === 0 ? resolve() : reject(new Error(`ffmpeg exited with code ${String(code)}`)));
    });
  }

  async probe(file: string): Promise<{ duration: number; width: number; height: number }> {
    return await new Promise((resolve, reject) => {
      const child = spawn(binaryPath('ffprobe'), ['-v', 'error', '-show_entries', 'format=duration:stream=width,height', '-of', 'json', file], { windowsHide: true });
      let output = '';
      child.stdout.on('data', (chunk) => { output += String(chunk); });
      child.once('error', reject);
      child.once('close', (code) => {
        if (code !== 0) { reject(new Error('ffprobe failed')); return; }
        const data = JSON.parse(output) as { format: { duration: string }; streams: Array<{ width?: number; height?: number }> };
        resolve({ duration: Number(data.format.duration), width: data.streams[0]?.width ?? 0, height: data.streams[0]?.height ?? 0 });
      });
    });
  }

  async analyze(file: string): Promise<MediaAnalysis> {
    let log = '';
    await this.run(['-i', file, '-vf', "select='gt(scene,0.35)',showinfo", '-an', '-f', 'null', '-'], (line) => { log += line; });
    try {
      await this.run(['-i', file, '-vn', '-af', 'silencedetect=n=-30dB:d=0.8,astats=metadata=1:reset=1,ametadata=print', '-f', 'null', '-'], (line) => { log += line; });
    } catch { /* A silent video still has useful scene analysis. */ }
    const scenes = [...log.matchAll(/showinfo[^\n]*pts_time:([\d.]+)/g)].map((match) => Number(match[1])).filter(Number.isFinite);
    const starts = [...log.matchAll(/silence_start:\s*([\d.]+)/g)].map((match) => Number(match[1]));
    const ends = [...log.matchAll(/silence_end:\s*([\d.]+)/g)].map((match) => Number(match[1]));
    const silences = starts.map((start, index) => ({ start, end: ends[index] ?? start + 0.8 }));
    const times = [...log.matchAll(/pts_time:([\d.]+)/g)].map((match) => Number(match[1]));
    const rms = [...log.matchAll(/lavfi\.astats\.Overall\.RMS_level=([-\d.]+)/g)].map((match) => Number(match[1]));
    const audioPeaks = rms.map((level, index) => ({ time: times[index] ?? index, score: Math.max(0, Math.min(1, (level + 45) / 45)) }));
    return { scenes: [...new Set(scenes)].sort((a, b) => a - b), silences, audioPeaks };
  }

  async render(source: string, start: number, end: number, output: string, options: RenderOptions): Promise<void> {
    const duration = Math.max(1, end - start);
    const square = options.aspect === '1:1';
    const landscape = options.aspect === '16:9';
    const width = landscape ? 1920 : 1080;
    const height = square ? 1080 : landscape ? 1080 : 1920;
    const foregroundWidth = landscape ? 1920 : square ? 1080 : 900;
    const background = options.blurBackground
      ? `[0:v]split[orig][bg];[bg]scale=${String(width)}:${String(height)}:force_original_aspect_ratio=increase,crop=${String(width)}:${String(height)},boxblur=20:1[base];[orig]scale=${String(foregroundWidth)}:-2[fg];[base][fg]overlay=(W-w)/2:(H-h)/2[composite]`
      : `[0:v]scale=${String(width)}:${String(height)}:force_original_aspect_ratio=decrease,pad=${String(width)}:${String(height)}:(ow-iw)/2:(oh-ih)/2:black[composite]`;
    const effects: string[] = [];
    if (options.smartZoom) effects.push(`scale=w='iw*(1+0.10*t/${String(duration)})':h='ih*(1+0.10*t/${String(duration)})':eval=frame,crop=${String(width)}:${String(height)}`);
    if (options.captionsPath) effects.push(`subtitles='${subtitlePath(options.captionsPath)}'${options.fontsDirectory?`:fontsdir='${subtitlePath(options.fontsDirectory)}'`:''}:force_style='FontName=Inter,FontSize=22,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=3,Shadow=1,MarginV=110,Alignment=2'`);
    const filter = `${background};[composite]${effects.length ? effects.join(',') : 'null'}[video]`;
    const args = ['-ss', String(Math.max(0, start - 0.3)), '-i', source];
    if (options.musicPath) args.push('-stream_loop', '-1', '-i', options.musicPath);
    args.push('-t', String(duration + 0.6), '-filter_complex', options.musicPath
      ? `${filter};[1:a]volume=${String(options.musicVolume ?? 0.12)},asplit=2[musicduck][musicmix];[0:a][musicduck]sidechaincompress=threshold=0.1:ratio=4[ducked];[ducked][musicmix]amix=inputs=2:duration=first:weights='1 0.15'[audio]`
      : filter, '-map', '[video]');
    if (options.musicPath) args.push('-map', '[audio]'); else args.push('-map', '0:a?');
    args.push('-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-c:a', 'aac', '-b:a', '192k', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', output);
    await this.run(args);
  }

  async frame(source: string, time: number, output: string): Promise<void> {
    await this.run(['-ss', String(time), '-i', source, '-frames:v', '1', '-q:v', '2', output]);
  }
}

export function outputFor(source: string, name: string): string { return path.join(path.dirname(source), name); }
