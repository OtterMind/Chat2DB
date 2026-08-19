export interface DataSourceIdentityColorSource {
  identityColor?: string | null;
}

export function normalizeIdentityColor(color?: string | null): string | null {
  const normalizedColor = color?.trim().toUpperCase();
  return normalizedColor && /^#[0-9A-F]{6}$/.test(normalizedColor) ? normalizedColor : null;
}

export function resolveDataSourceIdentityColor(
  source?: DataSourceIdentityColorSource | null,
): string | null {
  return normalizeIdentityColor(source?.identityColor);
}

export function withIdentityColorAlpha(color: string, opacity: number): string {
  const normalizedOpacity = Math.min(1, Math.max(0, opacity));
  const trimmedColor = color.trim();
  const shortHexMatch = /^#([0-9a-f])([0-9a-f])([0-9a-f])$/i.exec(trimmedColor);
  const hexMatch = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(trimmedColor);
  const rgbMatch = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/i.exec(trimmedColor);

  const channels = shortHexMatch
    ? shortHexMatch.slice(1).map((channel) => parseInt(`${channel}${channel}`, 16))
    : hexMatch
    ? hexMatch.slice(1).map((channel) => parseInt(channel, 16))
    : rgbMatch
    ? rgbMatch.slice(1).map(Number)
    : null;

  if (channels) {
    return `rgba(${channels[0]}, ${channels[1]}, ${channels[2]}, ${normalizedOpacity})`;
  }

  const percentage = Number((normalizedOpacity * 100).toFixed(2));
  return `color-mix(in srgb, ${trimmedColor} ${percentage}%, transparent)`;
}
