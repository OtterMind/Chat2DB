import { clientRuntime } from '@client-runtime';
import Dexie, { Table } from 'dexie';
import { dbSchemaV1 } from './schema';
import { DataSourceTree } from './types';

// Create database class
class Chat2dbDatabase extends Dexie {
  dataSourceTree!: Table<DataSourceTree>;

  constructor() {
    super(clientRuntime.dexieDatabaseName);

    // Define database schema
    this.version(1).stores(dbSchemaV1);
  }
}

// Create database singleton
export const db = new Chat2dbDatabase();
