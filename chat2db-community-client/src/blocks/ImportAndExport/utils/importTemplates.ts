import { ImportExportFileType } from '@/constants/importExport';
import type { ICsvOptions, IExcelOptions, IJsonOptions } from '@/typings/importExport';

/**
 * Import format presets ("templates").
 *
 * A template is the option group of one file format - the CSV, Excel, or JSON options - saved under
 * one name, together with the file format it belongs to. Templates are global: the backend keeps one
 * file per template under the workspace storage directory
 * (`<user data directory>/<env>/storage/import-templates/<type>/<name>.json`), so a preset
 * survives a browser change, a port change, and a backend restart, and the wizard offers the presets
 * of the file it is importing.
 *
 * The pure helpers below own normalization. Storage access goes through the service requests, which
 * are imported lazily so these helpers stay usable in the Node tests.
 */

/** Legacy per-table localStorage prefix; only the one-shot migration still reads it. */
export const IMPORT_TEMPLATE_STORAGE_PREFIX = 'chat2db.importTemplate';

const LEGACY_STORAGE_KEY_PREFIX = `${IMPORT_TEMPLATE_STORAGE_PREFIX}.`;

/** Format options a template carries: exactly the group of the file being imported. */
export interface ImportTemplateFormatOptions {
  csvOptions?: ICsvOptions;
  excelOptions?: IExcelOptions;
  jsonOptions?: IJsonOptions;
}

/** File formats a template can carry: the ones whose format step owns an option group. */
export const IMPORT_TEMPLATE_FILE_TYPES: readonly ImportExportFileType[] = [
  ImportExportFileType.CSV,
  ImportExportFileType.XLSX,
  ImportExportFileType.XLS,
  ImportExportFileType.JSON,
];

export interface ImportTemplate {
  name: string;
  /** Epoch milliseconds; used for ordering and display only. */
  createdAt: number;
  /**
   * File format this preset applies to. Absent means the format is unknown: the stored document
   * said nothing and its option group could not be attributed, which is what a legacy preset the
   * wizard still has to keep reports.
   */
  fileType?: ImportExportFileType;
  formatOptions?: ImportTemplateFormatOptions;
}

/** The subset of the Storage API the one-shot legacy migration needs, so tests can supply a stub. */
export interface ImportTemplateStorage {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
  removeItem: (key: string) => void;
  /** Legacy keys are discovered by enumeration; a storage stub may omit both. */
  length?: number;
  key?: (index: number) => string | null;
}

/** The template API these helpers need; a test passes its own stub instead of the service. */
export interface ImportTemplateStore {
  /** Reads every stored template, or only the ones of one file format. */
  list: (fileType?: string) => Promise<unknown>;
  save: (template: ImportTemplate) => Promise<unknown>;
  remove: (name: string, fileType?: string) => Promise<unknown>;
}

export interface ImportTemplateAccessOptions {
  store?: ImportTemplateStore;
  /** Defaults to the browser localStorage; null means "no legacy storage to migrate". */
  storage?: ImportTemplateStorage | null;
  /** Narrows a read to one file format; absent reads every format. */
  fileType?: string;
}

export type ImportTemplateSaveStatus = 'created' | 'overwritten' | 'nameExists' | 'invalidName' | 'storageUnavailable';

export interface ImportTemplateSaveResult {
  status: ImportTemplateSaveStatus;
  /** The list as it is stored after the attempt. */
  templates: ImportTemplate[];
}

export interface ImportTemplateDeleteResult {
  status: 'deleted' | 'missing' | 'storageUnavailable';
  templates: ImportTemplate[];
}

export interface ImportTemplateMigrationResult {
  /** Templates written to the shared store. */
  migrated: number;
  /** Legacy keys kept for the next visit because the write failed. */
  keptKeys: string[];
}

const isNonEmptyString = (value: unknown): value is string => typeof value === 'string' && value.trim() !== '';

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : undefined;

/** localStorage when it is reachable; null on the server, in tests, or when access is denied. */
export const getImportTemplateStorage = (): ImportTemplateStorage | null => {
  try {
    if (typeof window === 'undefined') {
      return null;
    }
    return window.localStorage ?? null;
  } catch {
    return null;
  }
};

/** Keeps the option groups the wizard can restore; a template without any is not usable. */
const normalizeFormatOptions = (value: unknown): ImportTemplateFormatOptions | undefined => {
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  const formatOptions: ImportTemplateFormatOptions = {
    csvOptions: asRecord(record.csvOptions) as ICsvOptions | undefined,
    excelOptions: asRecord(record.excelOptions) as IExcelOptions | undefined,
    jsonOptions: asRecord(record.jsonOptions) as IJsonOptions | undefined,
  };
  if (!formatOptions.csvOptions && !formatOptions.excelOptions && !formatOptions.jsonOptions) {
    return undefined;
  }
  Object.keys(formatOptions).forEach((key) => {
    if (formatOptions[key as keyof ImportTemplateFormatOptions] === undefined) {
      delete formatOptions[key as keyof ImportTemplateFormatOptions];
    }
  });
  return formatOptions;
};

/** The stored file type, or undefined for a blank, unknown, or unsupported value. */
export const normalizeTemplateFileType = (value: unknown): ImportExportFileType | undefined => {
  if (typeof value !== 'string') {
    return undefined;
  }
  const candidate = value.trim().toUpperCase();
  return IMPORT_TEMPLATE_FILE_TYPES.find((fileType) => fileType === candidate);
};

/**
 * The file type an option group belongs to, used for documents that do not record one. Excel
 * options are shared by XLS and XLSX, so they resolve to the modern XLSX.
 */
export const inferTemplateFileType = (
  formatOptions?: ImportTemplateFormatOptions,
): ImportExportFileType | undefined => {
  if (!formatOptions) {
    return undefined;
  }
  if (formatOptions.csvOptions) {
    return ImportExportFileType.CSV;
  }
  if (formatOptions.excelOptions) {
    return ImportExportFileType.XLSX;
  }
  if (formatOptions.jsonOptions) {
    return ImportExportFileType.JSON;
  }
  return undefined;
};

/** The option group the template of a file type carries; undefined for a format without one. */
export const templateOptionKey = (fileType?: string | null): keyof ImportTemplateFormatOptions | undefined => {
  switch (normalizeTemplateFileType(fileType)) {
    case ImportExportFileType.CSV:
      return 'csvOptions';
    case ImportExportFileType.JSON:
      return 'jsonOptions';
    case ImportExportFileType.XLS:
    case ImportExportFileType.XLSX:
      return 'excelOptions';
    default:
      return undefined;
  }
};

/** Drops entries a corrupted store may contain; never throws. */
export const normalizeImportTemplate = (value: unknown): ImportTemplate | null => {
  const record = asRecord(value);
  if (!record || !isNonEmptyString(record.name)) {
    return null;
  }
  const formatOptions = normalizeFormatOptions(record.formatOptions);
  if (!formatOptions) {
    return null;
  }
  // A preset written before templates carried a file type still reports the format its option
  // group belongs to, and only an unattributable one stays unknown.
  const fileType = normalizeTemplateFileType(record.fileType) ?? inferTemplateFileType(formatOptions);
  return {
    name: record.name.trim(),
    createdAt: typeof record.createdAt === 'number' && Number.isFinite(record.createdAt) ? record.createdAt : 0,
    ...(fileType ? { fileType } : {}),
    formatOptions,
  };
};

/**
 * The presets the wizard may offer for one file type: the ones stored for it, plus the ones whose
 * type is unknown. An unknown preset is a legacy document nothing could attribute, so it stays
 * applicable instead of being lost; a preset of another file type is never offered.
 */
export const filterImportTemplatesByFileType = (
  templates: ImportTemplate[],
  fileType?: string | null,
): ImportTemplate[] => {
  if (fileType === undefined || fileType === null || fileType === '') {
    return templates;
  }
  const wanted = normalizeTemplateFileType(fileType);
  if (!wanted) {
    // A format the wizard cannot import at all (SQL, ZIP) has no presets.
    return [];
  }
  return templates.filter((template) => !template.fileType || template.fileType === wanted);
};

/**
 * Normalizes a stored payload into the templates the wizard can use. Accepts both the service list
 * and a raw `{"version":1,...}` document; anything else reads as no templates.
 */
export const normalizeImportTemplates = (value: unknown): ImportTemplate[] => {
  const list = Array.isArray(value) ? value : asRecord(value)?.templates;
  if (!Array.isArray(list)) {
    return [];
  }
  const seen = new Set<string>();
  const templates: ImportTemplate[] = [];
  list.forEach((item) => {
    const template = normalizeImportTemplate(item);
    if (template && !seen.has(template.name)) {
      seen.add(template.name);
      templates.push(template);
    }
  });
  return templates;
};

/** Parses a stored JSON payload; malformed or unexpected content comes back as an empty list. */
export const parseImportTemplates = (raw: string | null): ImportTemplate[] => {
  if (!isNonEmptyString(raw)) {
    return [];
  }
  try {
    return normalizeImportTemplates(JSON.parse(raw as string));
  } catch {
    return [];
  }
};

let defaultStore: ImportTemplateStore | undefined;

/**
 * The service-backed store. It is loaded on first use so the pure helpers of this module can be
 * imported outside a browser runtime, where the HTTP client is not available.
 */
const resolveStore = async (store?: ImportTemplateStore): Promise<ImportTemplateStore> => {
  if (store) {
    return store;
  }
  if (!defaultStore) {
    const { default: importExportServices } = await import('@/service/importExport');
    defaultStore = {
      list: (fileType) => importExportServices.listImportTemplates(fileType ? { fileType } : undefined),
      save: (template) => importExportServices.saveImportTemplate(template),
      remove: (name, fileType) => importExportServices.deleteImportTemplate(fileType ? { name, fileType } : { name }),
    };
  }
  return defaultStore;
};

/** Lists the stored templates; an unreachable store reads as "no templates" like the old one did. */
export const listImportTemplates = async (
  options: ImportTemplateAccessOptions = {},
): Promise<ImportTemplate[]> => {
  try {
    const store = await resolveStore(options.store);
    return normalizeImportTemplates(await store.list(options.fileType));
  } catch {
    return [];
  }
};

/**
 * Creates or replaces a template by name. A duplicate name without `overwrite` reports
 * `nameExists` so the caller can ask the user to overwrite or rename, and a failed write reports
 * `storageUnavailable` so the caller can surface it instead of pretending it was saved.
 */
export const saveImportTemplate = async (
  template: ImportTemplate,
  options: ImportTemplateAccessOptions & { overwrite?: boolean } = {},
): Promise<ImportTemplateSaveResult> => {
  const name = typeof template.name === 'string' ? template.name.trim() : '';
  // A name identifies one template across file types, so the duplicate check reads every stored
  // type: saving "preset" as JSON replaces the stored "preset" instead of silently shadowing it.
  const existing = await listImportTemplates({ ...options, fileType: undefined });
  if (!name || !template.formatOptions) {
    return { status: 'invalidName', templates: existing };
  }
  const normalized: ImportTemplate = { ...template, name };
  const index = existing.findIndex((item) => item.name === name);
  if (index >= 0 && !options.overwrite) {
    return { status: 'nameExists', templates: existing };
  }
  try {
    const store = await resolveStore(options.store);
    const stored = normalizeImportTemplates(await store.save(normalized));
    return {
      status: index >= 0 ? 'overwritten' : 'created',
      // The store answers with the stored list; an unusable answer still means the template was
      // written, so the locally known state stands in for it.
      templates: stored.length ? stored : replaceTemplate(existing, normalized),
    };
  } catch {
    return { status: 'storageUnavailable', templates: existing };
  }
};

export const deleteImportTemplate = async (
  name: string,
  options: ImportTemplateAccessOptions = {},
): Promise<ImportTemplateDeleteResult> => {
  const existing = await listImportTemplates(options);
  const known = existing.some((template) => template.name === name);
  try {
    const store = await resolveStore(options.store);
    const stored = normalizeImportTemplates(await store.remove(name, options.fileType));
    return {
      status: known ? 'deleted' : 'missing',
      templates: stored.length ? stored : existing.filter((template) => template.name !== name),
    };
  } catch {
    return { status: 'storageUnavailable', templates: existing };
  }
};

/**
 * Moves presets written by the previous per-table localStorage version into the shared store, then
 * drops the legacy keys. Only entries that carry format options are migrated: a preset without
 * options has nothing to restore. A failed write keeps the keys so the next visit retries, and
 * saving the same name twice is idempotent, so a retry cannot duplicate a preset.
 */
export const migrateLegacyImportTemplates = async (
  options: ImportTemplateAccessOptions = {},
): Promise<ImportTemplateMigrationResult> => {
  const storage = options.storage === undefined ? getImportTemplateStorage() : options.storage;
  const legacyKeys = listLegacyStorageKeys(storage);
  if (!legacyKeys.length) {
    return { migrated: 0, keptKeys: [] };
  }
  const templates = collectLegacyTemplates(storage, legacyKeys);
  if (!templates.length) {
    // Nothing usable: the legacy entries are dropped instead of being re-read on every visit.
    legacyKeys.forEach((key) => removeStoredItem(storage, key));
    return { migrated: 0, keptKeys: [] };
  }
  const store = await resolveStore(options.store).catch(() => undefined);
  if (!store) {
    return { migrated: 0, keptKeys: legacyKeys };
  }
  let migrated = 0;
  try {
    // Sequential on purpose: the first failure has to stop the run so the untouched keys can be
    // retried as a whole.
    for (const template of templates) {
      // eslint-disable-next-line no-await-in-loop
      await store.save(template);
      migrated += 1;
    }
  } catch {
    return { migrated, keptKeys: legacyKeys };
  }
  legacyKeys.forEach((key) => removeStoredItem(storage, key));
  return { migrated, keptKeys: [] };
};

/** First read of the wizard: migrate the legacy entries, then list everything stored. */
export const loadImportTemplates = async (
  options: ImportTemplateAccessOptions = {},
): Promise<ImportTemplate[]> => {
  await migrateLegacyImportTemplates(options);
  return listImportTemplates(options);
};

const replaceTemplate = (templates: ImportTemplate[], template: ImportTemplate): ImportTemplate[] => {
  const index = templates.findIndex((item) => item.name === template.name);
  if (index < 0) {
    return [...templates, template];
  }
  return templates.map((item, itemIndex) => (itemIndex === index ? template : item));
};

const listLegacyStorageKeys = (storage: ImportTemplateStorage | null): string[] => {
  if (!storage || typeof storage.length !== 'number' || typeof storage.key !== 'function') {
    return [];
  }
  const keys: string[] = [];
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index);
    if (key && key.startsWith(LEGACY_STORAGE_KEY_PREFIX)) {
      keys.push(key);
    }
  }
  return keys;
};

/** One template per name: the newest createdAt wins when two tables stored the same name. */
const collectLegacyTemplates = (storage: ImportTemplateStorage | null, keys: string[]): ImportTemplate[] => {
  if (!storage) {
    return [];
  }
  const byName = new Map<string, ImportTemplate>();
  keys.forEach((key) => {
    let raw: string | null = null;
    try {
      raw = storage.getItem(key);
    } catch {
      raw = null;
    }
    parseImportTemplates(raw).forEach((template) => {
      const existing = byName.get(template.name);
      if (!existing || template.createdAt >= existing.createdAt) {
        byName.set(template.name, template);
      }
    });
  });
  return Array.from(byName.values());
};

const removeStoredItem = (storage: ImportTemplateStorage | null, key: string) => {
  try {
    storage?.removeItem(key);
  } catch {
    // A storage that refuses the delete only costs one more migration attempt.
  }
};
