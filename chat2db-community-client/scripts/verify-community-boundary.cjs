#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const root = process.cwd();
const sourceRoot = path.join(root, 'src');
const serverRoot = path.resolve(root, '..', 'chat2db-community-server');

const commercialPaths = [
  '.umirc.desktop.ts',
  '.umirc.offline.ts',
  'scripts/modify-package-name.js',
  'src/assets/logo/pro',
  'src/assets/logo/local',
  'src/blocks/PersonalCenter',
  'src/blocks/Setting/DeviceCer',
  'src/blocks/Setting/Invite',
  'src/blocks/Setting/License',
  'src/blocks/Setting/Personal',
  'src/community-stubs',
  'src/components/Feedback',
  'src/components/GuideDialog',
  'src/components/LicenseDialog',
  'src/components/Price',
  'src/components/PurchaseDetails',
  'src/layouts/unLoginLayout',
  'src/pages/invite',
  'src/pages/login',
  'src/pages/price',
  'src/pages/purchase',
  'src/pages/main/organization',
  'src/service/enterprise',
  'src/service/invitation.ts',
  'src/service/license.ts',
  'src/service/pricing.ts',
  'src/store/organization',
  'src/store/user',
  'src/typings/enterprise',
  'src/typings/invitation.ts',
  'src/typings/license.ts',
  'src/typings/pricing.ts',
  'src/utils/authPerm.ts',
  'src/utils/googleAds.ts',
  'src/utils/price.ts',
];

const containsFiles = (entryPath) => {
  if (!fs.existsSync(entryPath)) return false;
  if (fs.statSync(entryPath).isFile()) return true;
  return fs.readdirSync(entryPath, { withFileTypes: true }).some((entry) =>
    entry.isFile() || containsFiles(path.join(entryPath, entry.name)),
  );
};

for (const relativePath of commercialPaths) {
  if (containsFiles(path.join(root, relativePath))) {
    throw new Error(`Community must not contain commercial implementation: ${relativePath}`);
  }
}

const forbiddenMarkers = [
  'CHAT2DB_' + 'PRODUCT',
  'ENTERPRISE_' + 'DELIVERY',
  'enterprise-' + 'delivery',
  'Runtime' + 'EditionConfig',
  'Edition' + 'UiExtension',
  '@' + 'commercial',
  '@' + 'product-ui',
  'Commercial' + 'GlobalComponentExtras',
  'community-' + 'stubs',
  '/api/' + 'enterprise',
  'chat2db-' + 'pro',
  'chat2db-' + 'local',
  'Chat2DB ' + 'Pro',
  'Chat2DB ' + 'Local',
  'Pro ' + 'Member',
];

const productionFiles = [];
const pending = [sourceRoot];
while (pending.length) {
  const current = pending.pop();
  for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
    const entryPath = path.join(current, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === '.umi' || entry.name === '.umi-production') continue;
      pending.push(entryPath);
    } else if (/\.(?:js|jsx|ts|tsx)$/.test(entry.name) && !/\.test\.[^.]+$/.test(entry.name)) {
      productionFiles.push(entryPath);
    }
  }
}

const serverPending = [serverRoot];
while (serverPending.length) {
  const current = serverPending.pop();
  for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
    const entryPath = path.join(current, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'target') continue;
      serverPending.push(entryPath);
    } else if (
      entryPath.includes(`${path.sep}src${path.sep}main${path.sep}`) &&
      /\.(?:html|java|properties|xml|ya?ml)$/.test(entry.name)
    ) {
      productionFiles.push(entryPath);
    }
  }
}

for (const filePath of [...productionFiles, path.join(root, '.umirc.ts')]) {
  const source = fs.readFileSync(filePath, 'utf8');
  for (const marker of forbiddenMarkers) {
    if (source.includes(marker)) {
      throw new Error(`Community boundary marker ${marker} found in ${path.relative(root, filePath)}`);
    }
  }
}

const packageJson = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
for (const script of Object.keys(packageJson.scripts || {})) {
  const segments = script.toLowerCase().split(':');
  if (
    (segments[0] === 'build' || segments[0] === 'start') &&
    segments.some((segment) => ['pro', 'local', 'enterprise', 'delivery'].includes(segment))
  ) {
    throw new Error(`Community package exposes a commercial product script: ${script}`);
  }
}

console.log('[verify-community-boundary] Community has no commercial product implementation or selector.');
