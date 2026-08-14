import {app} from 'electron'; import {existsSync} from 'node:fs'; import path from 'node:path';
export type BinaryName='ffmpeg'|'ffprobe'|'yt-dlp';
export function binaryPath(name:BinaryName):string {const ext=process.platform==='win32'?'.exe':''; const file=`${name}${ext}`; const value=app.isPackaged?path.join(process.resourcesPath,'binaries',file):path.join(process.cwd(),'resources','binaries',file); if(!existsSync(value)) throw new Error(`Required binary not found: ${value}`); return value}
