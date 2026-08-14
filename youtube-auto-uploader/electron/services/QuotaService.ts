import type Database from 'better-sqlite3';

export class QuotaService {
  constructor(private readonly db: Database.Database, private readonly dailyLimit = 10_000) {}
  private key(date = new Date()): string { const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'America/Los_Angeles',year:'numeric',month:'2-digit',day:'2-digit'}).format(date);return `quota_used_${parts}`; }
  used(): number {
    const row = this.db.prepare('SELECT value FROM settings WHERE key=?').get(this.key()) as { value: string } | undefined;
    if (!row) return 0;
    try { return Number(JSON.parse(row.value)); } catch { return Number(row.value) || 0; }
  }
  remaining(): number { return Math.max(0, this.dailyLimit - this.used()); }
  consume(units: number): void {
    const next = this.used() + units;
    this.db.prepare('INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').run(this.key(), JSON.stringify(next));
  }
  require(units: number): void {
    if (this.remaining() < units) throw new Error('quotaExceeded: insufficient estimated YouTube API quota remaining today');
  }
  state(): { used: number; remaining: number; limit: number } { return { used: this.used(), remaining: this.remaining(), limit: this.dailyLimit }; }
}
