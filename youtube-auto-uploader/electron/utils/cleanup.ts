import { readdir, rm, stat } from 'node:fs/promises';
import path from 'node:path';

export async function sweepDirectory(directory: string, olderThanMs = 24 * 60 * 60 * 1000): Promise<number> {
  let removed = 0;
  let entries: string[];
  try { entries = await readdir(directory); } catch { return 0; }
  await Promise.all(entries.map(async (entry) => {
    const target = path.join(directory, entry);
    try {
      const info = await stat(target);
      if (Date.now() - info.mtimeMs > olderThanMs) { await rm(target, { recursive: true, force: true }); removed++; }
    } catch { /* Another cleanup task may have removed it. */ }
  }));
  return removed;
}
