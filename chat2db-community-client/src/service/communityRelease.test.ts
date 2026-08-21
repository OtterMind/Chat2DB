import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  compareCommunityVersions,
  fetchLatestCommunityRelease,
} from './communityRelease';

test('compares numeric Community versions', () => {
  assert.equal(compareCommunityVersions('5.3.10', '5.3.4'), 1);
  assert.equal(compareCommunityVersions('5.3.4', '5.3.4'), 0);
  assert.equal(compareCommunityVersions('5.2.9', '5.3.0'), -1);
});

test('accepts only stable Chat2DB GitHub Releases', async () => {
  const release = await fetchLatestCommunityRelease(async () => new Response(JSON.stringify({
    tag_name: 'v5.3.4',
    html_url: 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.4',
    body: 'Fixes',
    published_at: '2026-08-20T16:12:58Z',
    draft: false,
    prerelease: false,
  }), { status: 200 }));

  assert.deepEqual(release, {
    version: '5.3.4',
    releaseUrl: 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.4',
    releaseNotes: 'Fixes',
    publishedAt: '2026-08-20T16:12:58Z',
  });
});

test('rejects prereleases and untrusted Release URLs', async () => {
  const response = (payload: unknown) => async () => new Response(JSON.stringify(payload), { status: 200 });
  await assert.rejects(() => fetchLatestCommunityRelease(response({
    tag_name: 'v5.3.5-beta.1',
    html_url: 'https://github.com/OtterMind/Chat2DB/releases/tag/v5.3.5-beta.1',
    prerelease: true,
  })));
  await assert.rejects(() => fetchLatestCommunityRelease(response({
    tag_name: 'v5.3.4',
    html_url: 'https://example.com/release/v5.3.4',
    prerelease: false,
  })));
});
