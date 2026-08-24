import createRequest from './base';

export interface DataWikiColumn {
  name: string;
  dataType?: string;
  sourceComment?: string;
  businessName?: string;
  businessDescription?: string;
  sampleValues?: string;
  enumDescription?: string;
}

export interface DataWikiResource {
  id: string;
  dataSourceId: number;
  dataSourceName?: string;
  databaseName?: string;
  schemaName?: string;
  tableName: string;
  tableType?: string;
  sourceComment?: string;
  businessName?: string;
  businessDescription?: string;
  columns: DataWikiColumn[];
}

export interface DataWikiDefinition {
  id: string;
  name: string;
  description?: string;
  resources: DataWikiResource[];
  revision: number;
  gmtCreate: string | number;
  gmtModified: string | number;
}

export interface DataWikiDocument {
  path: string;
  title: string;
  kind: 'README' | 'TABLE';
  content?: string;
}

export interface DataWikiDocumentBundle {
  dataWikiId: string;
  revision: number;
  rootDirectory: string;
  documents: DataWikiDocument[];
}

const list = createRequest<void, DataWikiDefinition[]>('/api/data-wikis');
const create = createRequest<{ name: string; description?: string }, DataWikiDefinition>('/api/data-wikis', {
  method: 'post',
});
const update = createRequest<DataWikiDefinition & { expectedRevision: number }, DataWikiDefinition>(
  '/api/data-wikis/:id',
  { method: 'post' },
);
const remove = createRequest<{ id: string; expectedRevision: number }, void>('/api/data-wikis/:id', {
  method: 'delete',
});
const markdown = createRequest<{ id: string }, string>('/api/data-wikis/:id/markdown');
const documents = createRequest<{ id: string }, DataWikiDocumentBundle>('/api/data-wikis/:id/documents');
const documentContent = createRequest<{ id: string; path: string }, string>(
  '/api/data-wikis/:id/documents/content',
);

export default { list, create, update, remove, markdown, documents, documentContent };
