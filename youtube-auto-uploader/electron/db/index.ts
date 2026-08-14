import Database from 'better-sqlite3';
import migration001 from './migrations/001_init.sql?raw';
import migration002 from './migrations/002_job_payload.sql?raw';

export class AppDatabase {
  readonly db: Database.Database;
  constructor(file: string) {
    this.db = new Database(file);
    this.db.pragma('journal_mode = WAL');
    this.migrate([{ version: 1, sql: migration001 }, { version: 2, sql: migration002 }]);
    this.db.prepare("UPDATE synced_videos SET status='pending' WHERE status IN ('downloading','uploading')").run();
  }
  private migrate(migrations: Array<{ version: number; sql: string }>): void {
    this.db.exec('CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY,value TEXT NOT NULL)');
    const row = this.db.prepare("SELECT value FROM settings WHERE key='schema_version'").get() as { value: string } | undefined;
    let version = Number(row?.value ?? 0);
    for (const migration of migrations) {
      if (migration.version <= version) continue;
      this.db.transaction(() => {
        this.db.exec(migration.sql);
        this.db.prepare("INSERT INTO settings(key,value) VALUES('schema_version',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").run(String(migration.version));
      })();
      version = migration.version;
    }
  }
  close(): void { this.db.close(); }
}
