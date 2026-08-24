#!/usr/bin/env node

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const clientRoot = path.resolve(__dirname, '..');
const repositoryRoot = path.resolve(clientRoot, '..');
const i18nRoot = path.join(clientRoot, 'src', 'i18n');
const sourceLocale = 'en-US';
const targetLocales = ['es-ES', 'ko-KR'];
const sourceHashesPath = path.join(__dirname, 'i18n-source-hashes.json');
const writeSourceHashes = process.argv.includes('--write-source-hashes');
const errors = [];

function addError(message) {
  errors.push(message);
}

function listTypeScriptModules(locale) {
  const directory = path.join(i18nRoot, locale);
  if (!fs.existsSync(directory)) {
    addError(`${locale}: locale directory does not exist`);
    return [];
  }
  return fs
    .readdirSync(directory)
    .filter((fileName) => fileName.endsWith('.ts'))
    .sort();
}

function findDuplicateKeys(filePath, sourceText) {
  const seen = new Set();
  const duplicateKeys = new Set();
  const propertyPattern = /^\s*(?:'([^']+)'|"([^"]+)"|([A-Za-z_$][\w$]*))\s*:/gm;
  let match;
  while ((match = propertyPattern.exec(sourceText)) !== null) {
    const key = match[1] ?? match[2] ?? match[3];
    if (seen.has(key)) {
      duplicateKeys.add(key);
    } else {
      seen.add(key);
    }
  }
  for (const key of duplicateKeys) {
    addError(`${path.relative(repositoryRoot, filePath)}: duplicate key ${key}`);
  }
}

function parseLocaleModule(filePath) {
  const sourceText = fs.readFileSync(filePath, 'utf8');
  findDuplicateKeys(filePath, sourceText);
  if (!/^\s*export\s+default\s+/.test(sourceText)) {
    addError(`${path.relative(repositoryRoot, filePath)}: missing default export`);
    return new Map();
  }
  const scriptText = sourceText.replace(/^\s*export\s+default\s+/, 'module.exports = ');
  const sandbox = {module: {exports: undefined}, exports: {}};
  let exportedValue;
  try {
    new vm.Script(scriptText, {filename: filePath}).runInNewContext(sandbox, {timeout: 1000});
    exportedValue = sandbox.module.exports;
  } catch (error) {
    addError(`${path.relative(repositoryRoot, filePath)}: ${error.message}`);
    return new Map();
  }

  if (!exportedValue || typeof exportedValue !== 'object' || Array.isArray(exportedValue)) {
    addError(`${path.relative(repositoryRoot, filePath)}: default export must be an object literal`);
    return new Map();
  }

  const entries = new Map();
  for (const [key, value] of Object.entries(exportedValue)) {
    if (typeof value !== 'string') {
      addError(`${path.relative(repositoryRoot, filePath)}: keys and values must be static strings`);
      continue;
    }
    entries.set(key, value);
  }
  return entries;
}

function sortedMatches(value, pattern) {
  return Array.from(value.matchAll(pattern), (match) => match[0]).sort();
}

function htmlTags(value) {
  return Array.from(value.matchAll(/<\/?([A-Za-z][\w-]*)\b[^>]*>/g), (match) => match[1].toLowerCase()).sort();
}

function compareStringContracts(context, sourceValue, targetValue) {
  const sourcePlaceholders = sortedMatches(sourceValue, /\{\d+\}/g);
  const targetPlaceholders = sortedMatches(targetValue, /\{\d+\}/g);
  if (JSON.stringify(sourcePlaceholders) !== JSON.stringify(targetPlaceholders)) {
    addError(`${context}: numbered placeholders differ (${sourcePlaceholders.join(', ')} != ${targetPlaceholders.join(', ')})`);
  }

  const sourceTags = htmlTags(sourceValue);
  const targetTags = htmlTags(targetValue);
  if (JSON.stringify(sourceTags) !== JSON.stringify(targetTags)) {
    addError(`${context}: HTML tags differ (${sourceTags.join(', ')} != ${targetTags.join(', ')})`);
  }
}

function compareKeySets(context, sourceEntries, targetEntries) {
  for (const key of sourceEntries.keys()) {
    if (!targetEntries.has(key)) {
      addError(`${context}: missing key ${key}`);
    }
  }
  for (const key of targetEntries.keys()) {
    if (!sourceEntries.has(key)) {
      addError(`${context}: unexpected key ${key}`);
    }
  }
}

function moduleHash(moduleName, entries) {
  const serializedEntries = Array.from(entries.entries()).sort(([left], [right]) => left.localeCompare(right));
  return crypto.createHash('sha256').update(`${moduleName}\0${JSON.stringify(serializedEntries)}`).digest('hex');
}

const sourceModules = listTypeScriptModules(sourceLocale);
const sourceContentModules = sourceModules.filter((moduleName) => moduleName !== 'index.ts');
const parsedSourceModules = new Map();
for (const moduleName of sourceContentModules) {
  parsedSourceModules.set(moduleName, parseLocaleModule(path.join(i18nRoot, sourceLocale, moduleName)));
}

for (const locale of targetLocales) {
  const targetModules = listTypeScriptModules(locale);
  if (JSON.stringify(targetModules) !== JSON.stringify(sourceModules)) {
    addError(`${locale}: module files differ from ${sourceLocale}`);
  }

  for (const moduleName of sourceContentModules) {
    const targetPath = path.join(i18nRoot, locale, moduleName);
    if (!fs.existsSync(targetPath)) {
      continue;
    }
    const sourceEntries = parsedSourceModules.get(moduleName);
    const targetEntries = parseLocaleModule(targetPath);
    compareKeySets(`${locale}/${moduleName}`, sourceEntries, targetEntries);
    for (const [key, sourceValue] of sourceEntries) {
      const targetValue = targetEntries.get(key);
      if (targetValue !== undefined) {
        compareStringContracts(`${locale}/${moduleName}:${key}`, sourceValue, targetValue);
      }
    }
  }
}

function parseProperties(filePath) {
  const entries = new Map();
  const physicalLines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/);
  const logicalLines = [];
  let currentLine = '';
  for (const physicalLine of physicalLines) {
    currentLine += physicalLine;
    const trailingSlashes = currentLine.match(/\\+$/)?.[0].length || 0;
    if (trailingSlashes % 2 === 1) {
      currentLine = currentLine.slice(0, -1);
      continue;
    }
    logicalLines.push(currentLine);
    currentLine = '';
  }
  if (currentLine) {
    logicalLines.push(currentLine);
  }

  for (const rawLine of logicalLines) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#') || line.startsWith('!')) {
      continue;
    }
    const match = line.match(/^([^=:\s]+)\s*(?:=|:)\s*(.*)$/);
    if (!match) {
      addError(`${path.relative(repositoryRoot, filePath)}: invalid properties line: ${line}`);
      continue;
    }
    const [, key, value] = match;
    if (entries.has(key) && entries.get(key) !== value) {
      addError(`${path.relative(repositoryRoot, filePath)}: conflicting duplicate key ${key}`);
    }
    entries.set(key, value);
  }
  return entries;
}

const propertyBundles = [
  path.join(
    repositoryRoot,
    'chat2db-community-server',
    'chat2db-community-start',
    'src',
    'main',
    'resources',
    'i18n',
  ),
  path.join(
    repositoryRoot,
    'chat2db-community-server',
    'chat2db-community-jcef',
    'src',
    'main',
    'resources',
    'i18n',
  ),
];

for (const bundleDirectory of propertyBundles) {
  const sourcePath = path.join(bundleDirectory, 'messages_en_US.properties');
  const sourceEntries = parseProperties(sourcePath);
  for (const locale of targetLocales) {
    const javaLocale = locale.replace('-', '_');
    const targetPath = path.join(bundleDirectory, `messages_${javaLocale}.properties`);
    if (!fs.existsSync(targetPath)) {
      addError(`${path.relative(repositoryRoot, targetPath)}: file does not exist`);
      continue;
    }
    const targetEntries = parseProperties(targetPath);
    compareKeySets(`${path.relative(repositoryRoot, targetPath)}`, sourceEntries, targetEntries);
    for (const [key, sourceValue] of sourceEntries) {
      const targetValue = targetEntries.get(key);
      if (targetValue !== undefined) {
        compareStringContracts(`${path.relative(repositoryRoot, targetPath)}:${key}`, sourceValue, targetValue);
      }
    }
  }
}

const jcefBundleDirectory = propertyBundles[1];
const jcefSourceEntries = parseProperties(path.join(jcefBundleDirectory, 'messages_en_US.properties'));
for (const locale of targetLocales) {
  const language = locale.split('-')[0];
  const targetPath = path.join(jcefBundleDirectory, `messages_${language}.properties`);
  if (!fs.existsSync(targetPath)) {
    addError(`${path.relative(repositoryRoot, targetPath)}: generic language bundle does not exist`);
    continue;
  }
  const targetEntries = parseProperties(targetPath);
  compareKeySets(`${path.relative(repositoryRoot, targetPath)}`, jcefSourceEntries, targetEntries);
  for (const [key, sourceValue] of jcefSourceEntries) {
    const targetValue = targetEntries.get(key);
    if (targetValue !== undefined) {
      compareStringContracts(`${path.relative(repositoryRoot, targetPath)}:${key}`, sourceValue, targetValue);
    }
  }
}

const readmeFiles = ['README.md', 'README_CN.md', 'README_JA.md', 'README_ES.md', 'README_KO.md'];
for (const readmeFile of readmeFiles) {
  const readmePath = path.join(repositoryRoot, readmeFile);
  if (!fs.existsSync(readmePath)) {
    addError(`${readmeFile}: file does not exist`);
    continue;
  }
  const content = fs.readFileSync(readmePath, 'utf8');
  for (const linkedReadme of readmeFiles) {
    if (!content.includes(`./${linkedReadme}`)) {
      addError(`${readmeFile}: language navigation is missing ${linkedReadme}`);
    }
  }
}

const currentSourceHashes = Object.fromEntries(
  targetLocales.map((locale) => [
    locale,
    Object.fromEntries(
      sourceContentModules.map((moduleName) => [moduleName, moduleHash(moduleName, parsedSourceModules.get(moduleName))]),
    ),
  ]),
);

if (writeSourceHashes) {
  fs.writeFileSync(
    sourceHashesPath,
    `${JSON.stringify({ version: 1, algorithm: 'sha256', locales: currentSourceHashes }, null, 2)}\n`,
  );
} else if (!fs.existsSync(sourceHashesPath)) {
  addError(`${path.relative(repositoryRoot, sourceHashesPath)}: source hash manifest does not exist`);
} else {
  const storedManifest = JSON.parse(fs.readFileSync(sourceHashesPath, 'utf8'));
  for (const locale of targetLocales) {
    for (const moduleName of sourceContentModules) {
      const expectedHash = currentSourceHashes[locale][moduleName];
      const storedHash = storedManifest.locales?.[locale]?.[moduleName];
      if (storedHash !== expectedHash) {
        addError(`${locale}/${moduleName}: English source changed; update the translation and its source hash`);
      }
    }
  }
}

if (errors.length > 0) {
  console.error(`i18n validation failed with ${errors.length} error(s):`);
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log(
  `Validated ${targetLocales.join(' and ')} against ${sourceContentModules.length} frontend modules, ${propertyBundles.length} properties bundles, and ${readmeFiles.length} READMEs.`,
);
