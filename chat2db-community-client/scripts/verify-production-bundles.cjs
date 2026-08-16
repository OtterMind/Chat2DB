const fs = require('fs');
const path = require('path');

const distDirectory = path.resolve(__dirname, '..', 'dist');
const invalidPatterns = [
  {
    expression: /\bclass\s+extends\s+null\b/g,
    description: 'class inherits from null',
  },
  {
    expression: /unused pure expression or super/g,
    description: 'Webpack emitted an invalid innerGraph placeholder',
  },
];

function collectJavaScriptFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      return collectJavaScriptFiles(entryPath);
    }
    return entry.isFile() && entry.name.endsWith('.js') ? [entryPath] : [];
  });
}

if (!fs.existsSync(distDirectory)) {
  console.error(`[error] production bundle directory does not exist: ${distDirectory}`);
  process.exit(1);
}

const findings = [];
for (const filePath of collectJavaScriptFiles(distDirectory)) {
  const source = fs.readFileSync(filePath, 'utf8');
  for (const { expression, description } of invalidPatterns) {
    const matches = source.match(expression);
    if (matches?.length) {
      findings.push({ filePath, description, count: matches.length });
    }
  }
}

if (findings.length) {
  for (const finding of findings) {
    console.error(
      `[error] ${path.relative(distDirectory, finding.filePath)}: ${finding.description} (${finding.count})`,
    );
  }
  process.exit(1);
}

console.log('[check] production bundles contain no invalid class inheritance');
