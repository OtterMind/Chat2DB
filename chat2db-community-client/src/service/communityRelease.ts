export const COMMUNITY_LATEST_RELEASE_API = 'https://api.github.com/repos/OtterMind/Chat2DB/releases/latest';

const VERSION_PATTERN = /^v(\d+)(?:\.(\d+))(?:\.(\d+))$/;
const RELEASE_URL_PREFIX = 'https://github.com/OtterMind/Chat2DB/releases/tag/';

export interface CommunityRelease {
  version: string;
  releaseUrl: string;
  releaseNotes: string;
  publishedAt: string;
}

export function compareCommunityVersions(left: string, right: string): number {
  const leftParts = left.split('.').map(Number);
  const rightParts = right.split('.').map(Number);
  for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
    const leftPart = leftParts[index] || 0;
    const rightPart = rightParts[index] || 0;
    if (leftPart !== rightPart) {
      return leftPart > rightPart ? 1 : -1;
    }
  }
  return 0;
}

export async function fetchLatestCommunityRelease(
  fetcher: typeof fetch = fetch,
  signal?: AbortSignal,
): Promise<CommunityRelease> {
  const response = await fetcher(COMMUNITY_LATEST_RELEASE_API, {
    headers: { Accept: 'application/vnd.github+json' },
    signal,
  });
  if (!response.ok) {
    throw new Error(`GitHub Release request failed: ${response.status}`);
  }

  const payload = (await response.json()) as {
    tag_name?: unknown;
    html_url?: unknown;
    body?: unknown;
    published_at?: unknown;
    draft?: unknown;
    prerelease?: unknown;
  };
  const tag = typeof payload.tag_name === 'string' ? payload.tag_name : '';
  const match = VERSION_PATTERN.exec(tag);
  if (!match || payload.draft !== false || payload.prerelease !== false) {
    throw new Error('GitHub Release metadata is invalid');
  }

  const releaseUrl = typeof payload.html_url === 'string' ? payload.html_url : '';
  if (releaseUrl !== `${RELEASE_URL_PREFIX}${tag}`) {
    throw new Error('GitHub Release URL is invalid');
  }

  return {
    version: `${match[1]}.${match[2]}.${match[3]}`,
    releaseUrl,
    releaseNotes: typeof payload.body === 'string' ? payload.body : '',
    publishedAt: typeof payload.published_at === 'string' ? payload.published_at : '',
  };
}
