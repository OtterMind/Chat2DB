import assert from 'node:assert/strict';
import test from 'node:test';
import { ImportExportFileType } from '@/constants/importExport';
import {
  deleteImportTemplate,
  filterImportTemplatesByFileType,
  listImportTemplates,
  loadImportTemplates,
  migrateLegacyImportTemplates,
  normalizeImportTemplates,
  parseImportTemplates,
  saveImportTemplate,
  templateOptionKey,
  type ImportTemplate,
  type ImportTemplateStorage,
  type ImportTemplateStore,
} from './importTemplates';

const csvOptions = (delimiter: string, headerRow = 1) => ({
  csvOptions: { encoding: 'UTF-8', delimiter, headerRow, hasHeader: true },
});
const jsonOptions = (dataPath = '$') => ({ jsonOptions: { encoding: 'UTF-8', structure: 'ARRAY', dataPath } });

const buildTemplate = (name: string, extra: Partial<ImportTemplate> = {}): ImportTemplate => ({
  name,
  createdAt: 1700000000000,
  fileType: ImportExportFileType.CSV,
  formatOptions: csvOptions(','),
  ...extra,
});

/** The templates are global, so the stub never looks at a data source, database, or table. */
const createStore = (seed: ImportTemplate[] = []) => {
  const state = {
    templates: [...seed],
    saved: [] as ImportTemplate[],
    removed: [] as { name: string; fileType?: string }[],
    listedWith: [] as (string | undefined)[],
    offline: false,
  };
  const failWhenOffline = () => {
    if (state.offline) {
      throw new Error('service unavailable');
    }
  };
  const store: ImportTemplateStore = {
    list: async (fileType) => {
      failWhenOffline();
      state.listedWith.push(fileType);
      return fileType ? state.templates.filter((template) => template.fileType === fileType) : state.templates;
    },
    save: async (template) => {
      failWhenOffline();
      state.saved.push(template);
      const index = state.templates.findIndex((item) => item.name === template.name);
      state.templates =
        index < 0
          ? [...state.templates, template]
          : state.templates.map((item, itemIndex) => (itemIndex === index ? template : item));
      return state.templates;
    },
    remove: async (name, fileType) => {
      failWhenOffline();
      state.removed.push({ name, fileType });
      state.templates = state.templates.filter((item) => item.name !== name);
      return state.templates;
    },
  };
  return { store, state };
};

const createStorage = (seed: Record<string, string> = {}) => {
  const entries = new Map(Object.entries(seed));
  return {
    entries,
    get length() {
      return entries.size;
    },
    key: (index: number) => Array.from(entries.keys())[index] ?? null,
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => {
      entries.set(key, value);
    },
    removeItem: (key: string) => {
      entries.delete(key);
    },
  } satisfies ImportTemplateStorage & { entries: Map<string, string> };
};

test('lists every stored preset and can ask the store for one file type', async () => {
  const { store, state } = createStore([
    buildTemplate('csv-semicolon', { formatOptions: csvOptions(';', 2) }),
    buildTemplate('json-preset', { fileType: ImportExportFileType.JSON, formatOptions: jsonOptions() }),
  ]);

  const all = await listImportTemplates({ store });
  const csvOnly = await listImportTemplates({ store, fileType: 'CSV' });

  assert.deepEqual(
    all.map(({ name }) => name),
    ['csv-semicolon', 'json-preset'],
  );
  assert.deepEqual(
    csvOnly.map(({ name }) => name),
    ['csv-semicolon'],
    'the file type narrows the read to the presets the wizard can apply',
  );
  assert.deepEqual(state.listedWith, [undefined, 'CSV']);
});

test('an unreachable store reads as an empty list', async () => {
  const { store, state } = createStore([buildTemplate('csv-semicolon')]);
  state.offline = true;

  assert.deepEqual(await listImportTemplates({ store }), []);
});

test('keeps only presets that carry format options', () => {
  assert.deepEqual(parseImportTemplates(null), []);
  assert.deepEqual(parseImportTemplates(''), []);
  assert.deepEqual(parseImportTemplates('not json'), []);
  assert.deepEqual(parseImportTemplates('{"name":"x"}'), []);
  assert.deepEqual(parseImportTemplates('[1, 2, null]'), []);
  assert.deepEqual(parseImportTemplates('[{"name":""}]'), []);
  assert.deepEqual(normalizeImportTemplates({ version: 1, name: 'single' }), []);

  const document = normalizeImportTemplates({
    version: 1,
    templates: [
      { name: 'valid', createdAt: 42, formatOptions: csvOptions(';', 3) },
      { name: 'valid', formatOptions: csvOptions(',', 1) },
      { name: 'mapping-era', columnMappings: { id: 'user_id' }, emptyAsNull: true },
      { name: 'no-options' },
      { formatOptions: csvOptions(',', 1) },
      null,
    ],
  });
  assert.deepEqual(document, [
    { name: 'valid', createdAt: 42, fileType: ImportExportFileType.CSV, formatOptions: csvOptions(';', 3) },
  ]);
  assert.deepEqual(normalizeImportTemplates([{ name: 'spaced  ', formatOptions: csvOptions(',', 1) }]), [
    { name: 'spaced', createdAt: 0, fileType: ImportExportFileType.CSV, formatOptions: csvOptions(',', 1) },
  ]);
});

test('reads the recorded file type and infers the one of a legacy preset', () => {
  const described = (value: unknown) =>
    normalizeImportTemplates(value).map(({ name, fileType }) => ({ name, fileType }));

  assert.deepEqual(
    described([
      // A stored preset names its type, whatever case the document used.
      { name: 'typed-json', fileType: 'json', formatOptions: jsonOptions() },
      // A legacy preset reports the type its option group belongs to.
      { name: 'legacy-csv', formatOptions: csvOptions(';', 2) },
      { name: 'legacy-json', formatOptions: jsonOptions('$.data') },
      { name: 'legacy-excel', formatOptions: { excelOptions: { sheetIndex: 1 } } },
      // The recorded type wins over the option group it was stored with.
      { name: 'typed-wins', fileType: 'CSV', formatOptions: jsonOptions() },
      // A type the wizard cannot import falls back to the option group.
      { name: 'unsupported-type', fileType: 'SQL', formatOptions: csvOptions(',') },
    ]),
    [
      { name: 'typed-json', fileType: 'JSON' },
      { name: 'legacy-csv', fileType: 'CSV' },
      { name: 'legacy-json', fileType: 'JSON' },
      { name: 'legacy-excel', fileType: 'XLSX' },
      { name: 'typed-wins', fileType: 'CSV' },
      { name: 'unsupported-type', fileType: 'CSV' },
    ],
  );
});

test('maps every file type to the option group its presets carry', () => {
  assert.equal(templateOptionKey('CSV'), 'csvOptions');
  assert.equal(templateOptionKey('json'), 'jsonOptions');
  assert.equal(templateOptionKey(ImportExportFileType.XLS), 'excelOptions');
  assert.equal(templateOptionKey(ImportExportFileType.XLSX), 'excelOptions');
  assert.equal(templateOptionKey('SQL'), undefined);
  assert.equal(templateOptionKey(null), undefined);
  assert.equal(templateOptionKey(undefined), undefined);
});

test('offers only the presets of the file type in use', () => {
  const templates: ImportTemplate[] = [
    buildTemplate('csv-preset'),
    buildTemplate('json-preset', { fileType: ImportExportFileType.JSON, formatOptions: jsonOptions() }),
    buildTemplate('excel-preset', {
      fileType: ImportExportFileType.XLSX,
      formatOptions: { excelOptions: { sheetIndex: 1 } },
    }),
    // A preset whose type nothing could attribute stays applicable instead of being lost.
    { name: 'unknown-type-preset', createdAt: 1, formatOptions: csvOptions(',') },
  ];
  const names = (fileType?: string) =>
    filterImportTemplatesByFileType(templates, fileType).map(({ name }) => name);

  assert.deepEqual(names('CSV'), ['csv-preset', 'unknown-type-preset']);
  assert.deepEqual(names('json'), ['json-preset', 'unknown-type-preset']);
  assert.deepEqual(names(ImportExportFileType.XLSX), ['excel-preset', 'unknown-type-preset']);
  assert.deepEqual(names(ImportExportFileType.XLS), ['unknown-type-preset'], 'a preset of XLSX is not an XLS one');
  assert.deepEqual(names('SQL'), [], 'a format without presets matches nothing');
  assert.deepEqual(names(), ['csv-preset', 'json-preset', 'excel-preset', 'unknown-type-preset']);
  assert.equal(templates.length, 4, 'filtering must not mutate the stored list');
});

test('creates a preset, asks before overwriting it, and replaces it on request', async () => {
  const { store, state } = createStore();

  const created = await saveImportTemplate(buildTemplate('csv-semicolon'), { store, fileType: 'CSV' });
  assert.equal(created.status, 'created');
  assert.deepEqual(
    created.templates.map(({ name }) => name),
    ['csv-semicolon'],
  );
  assert.deepEqual(state.saved[0], {
    name: 'csv-semicolon',
    createdAt: 1700000000000,
    fileType: 'CSV',
    formatOptions: csvOptions(','),
  });

  const duplicate = await saveImportTemplate(buildTemplate('csv-semicolon', { formatOptions: csvOptions(';', 4) }), {
    store,
    fileType: 'CSV',
  });
  assert.equal(duplicate.status, 'nameExists');
  assert.deepEqual(duplicate.templates[0].formatOptions, csvOptions(','));
  assert.equal(state.saved.length, 1, 'a duplicate name must not reach the store');

  const overwritten = await saveImportTemplate(buildTemplate('csv-semicolon', { formatOptions: csvOptions(';', 4) }), {
    store,
    fileType: 'CSV',
    overwrite: true,
  });
  assert.equal(overwritten.status, 'overwritten');
  assert.deepEqual(overwritten.templates[0].formatOptions, csvOptions(';', 4));

  const trimmed = await saveImportTemplate(buildTemplate('  spaced  '), { store });
  assert.equal(trimmed.status, 'created');
  assert.deepEqual(
    trimmed.templates.map(({ name }) => name),
    ['csv-semicolon', 'spaced'],
  );

  assert.equal((await saveImportTemplate(buildTemplate('   '), { store })).status, 'invalidName');
  const withoutOptions = buildTemplate('no-options');
  withoutOptions.formatOptions = undefined;
  assert.equal((await saveImportTemplate(withoutOptions, { store })).status, 'invalidName');
});

test('checks a duplicate name across every file type', async () => {
  const { store, state } = createStore([
    buildTemplate('shared', { fileType: ImportExportFileType.JSON, formatOptions: jsonOptions() }),
  ]);

  const created = await saveImportTemplate(buildTemplate('shared'), { store, fileType: 'CSV' });

  assert.equal(created.status, 'nameExists', 'a name identifies one preset, whatever its file type');
  assert.deepEqual(state.listedWith, [undefined], 'the duplicate check reads every type, not only the current one');
  assert.deepEqual(state.saved, []);
});

test('surfaces a failed write instead of pretending the preset was saved', async () => {
  const { store, state } = createStore([buildTemplate('keep')]);
  state.offline = true;

  const saved = await saveImportTemplate(buildTemplate('new'), { store });
  assert.equal(saved.status, 'storageUnavailable');
  assert.deepEqual(saved.templates.map(({ name }) => name), []);

  const deleted = await deleteImportTemplate('keep', { store });
  assert.equal(deleted.status, 'storageUnavailable');

  state.offline = false;
  assert.deepEqual(
    (await listImportTemplates({ store })).map(({ name }) => name),
    ['keep'],
  );
});

test('deletes by name, with the file type that helps find the file', async () => {
  const { store, state } = createStore([buildTemplate('keep'), buildTemplate('drop')]);

  const deleted = await deleteImportTemplate('drop', { store, fileType: 'CSV' });
  assert.equal(deleted.status, 'deleted');
  assert.deepEqual(
    deleted.templates.map(({ name }) => name),
    ['keep'],
  );
  assert.deepEqual(state.removed, [{ name: 'drop', fileType: 'CSV' }]);

  const missing = await deleteImportTemplate('never-saved', { store });
  assert.equal(missing.status, 'missing');
  assert.deepEqual(
    missing.templates.map(({ name }) => name),
    ['keep'],
  );
  assert.deepEqual(state.removed[1], { name: 'never-saved', fileType: undefined });
});

test('migrates the legacy per-table presets and drops the keys', async () => {
  const storage = createStorage({
    'chat2db.importTemplate.1.bench.users': JSON.stringify([
      {
        name: 'users-semicolon',
        createdAt: 1000,
        columnMappings: { id: 'user_id' },
        unmappedTarget: 'NULL',
        emptyAsNull: true,
        formatOptions: csvOptions(';', 2),
      },
    ]),
    'chat2db.importTemplate.1.bench.orders': JSON.stringify([
      { name: 'orders-comma', createdAt: 2000, formatOptions: csvOptions(',', 1) },
    ]),
    'unrelated.key': 'keep me',
  });
  const { store, state } = createStore();

  const result = await migrateLegacyImportTemplates({ store, storage });

  assert.equal(result.migrated, 2);
  assert.deepEqual(result.keptKeys, []);
  assert.deepEqual(
    state.saved.map(({ name }) => name),
    ['users-semicolon', 'orders-comma'],
  );
  assert.deepEqual(state.saved[0], {
    name: 'users-semicolon',
    createdAt: 1000,
    fileType: 'CSV',
    formatOptions: csvOptions(';', 2),
  });
  assert.deepEqual(Array.from(storage.entries.keys()), ['unrelated.key']);
});

test('keeps the legacy keys when the migration cannot write', async () => {
  const legacyKey = 'chat2db.importTemplate.1.bench.users';
  const storage = createStorage({
    [legacyKey]: JSON.stringify([{ name: 'users-semicolon', createdAt: 1000, formatOptions: csvOptions(';', 2) }]),
  });
  const { store, state } = createStore();
  state.offline = true;

  const result = await migrateLegacyImportTemplates({ store, storage });

  assert.equal(result.migrated, 0);
  assert.deepEqual(result.keptKeys, [legacyKey]);
  assert.deepEqual(Array.from(storage.entries.keys()), [legacyKey]);
});

test('drops legacy entries that hold no preset and ignores a storage without legacy keys', async () => {
  const storage = createStorage({
    'chat2db.importTemplate.1.bench.users': 'not json',
    'chat2db.importTemplate.1.bench.orders': JSON.stringify([{ name: 'mapping-era', columnMappings: { id: 'id' } }]),
  });
  const { store, state } = createStore();

  const result = await migrateLegacyImportTemplates({ store, storage });

  assert.equal(result.migrated, 0);
  assert.deepEqual(result.keptKeys, []);
  assert.deepEqual(state.saved, []);
  assert.deepEqual(Array.from(storage.entries.keys()), []);
  assert.deepEqual(await migrateLegacyImportTemplates({ store, storage: null }), { migrated: 0, keptKeys: [] });
  assert.deepEqual(await migrateLegacyImportTemplates({ store, storage: createStorage() }), {
    migrated: 0,
    keptKeys: [],
  });
});

test('the wizard reads the legacy presets once and then lists the shared store', async () => {
  const storage = createStorage({
    'chat2db.importTemplate.1.bench.users': JSON.stringify([
      { name: 'users-semicolon', createdAt: 1000, formatOptions: csvOptions(';', 2) },
    ]),
  });
  const { store, state } = createStore();

  const first = await loadImportTemplates({ store, storage, fileType: 'CSV' });
  const second = await loadImportTemplates({ store, storage, fileType: 'CSV' });

  assert.deepEqual(
    first.map(({ name }) => name),
    ['users-semicolon'],
  );
  assert.deepEqual(
    second.map(({ name }) => name),
    ['users-semicolon'],
  );
  assert.equal(state.saved.length, 1, 'the legacy entry is migrated once');
  assert.deepEqual(Array.from(storage.entries.keys()), []);
});
