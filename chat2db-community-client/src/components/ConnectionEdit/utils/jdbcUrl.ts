function isBracketedHost(host: string) {
  return host.startsWith('[') && host.endsWith(']');
}

export function stripJdbcHostBrackets(host: string) {
  if (isBracketedHost(host)) {
    return host.slice(1, -1);
  }
  return host;
}

function isIpv6LiteralHost(host: string) {
  const hostValue = stripJdbcHostBrackets(host.trim());
  if (!hostValue.includes(':')) {
    return false;
  }
  if (hostValue.includes('://') || hostValue.includes('/') || hostValue.includes('?') || hostValue.includes('#')) {
    return false;
  }

  const colonCount = (hostValue.match(/:/g) || []).length;
  if (colonCount < 2) {
    return false;
  }

  return /^[0-9a-fA-F:.]+(%[\w.-]+)?$/.test(hostValue);
}

export function formatJdbcHostForUrl(host: any) {
  const hostValue = host == null ? '' : String(host);
  const bareHost = stripJdbcHostBrackets(hostValue);
  if (isIpv6LiteralHost(bareHost)) {
    return `[${bareHost}]`;
  }
  return bareHost;
}

export function normalizeJdbcHostFromUrl(host: any) {
  const hostValue = host == null ? '' : String(host);
  return stripJdbcHostBrackets(hostValue);
}

export function getJdbcTemplateFieldNames(template: unknown) {
  const fieldNames = new Set<string>();
  const pattern = /{(.*?)}/g;
  const templateValue = template == null ? '' : String(template);
  let match: RegExpExecArray | null;

  while ((match = pattern.exec(templateValue)) !== null) {
    if (match[1]) {
      fieldNames.add(match[1]);
    }
  }

  return fieldNames;
}

export function shouldSyncJdbcUrlForField(fieldName: string | undefined, template: unknown) {
  if (!fieldName || fieldName === 'url' || fieldName === 'alias') {
    return true;
  }
  return getJdbcTemplateFieldNames(template).has(fieldName);
}
