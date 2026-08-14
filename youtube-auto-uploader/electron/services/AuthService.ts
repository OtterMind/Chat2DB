import type Database from 'better-sqlite3';
import type { CredentialVault } from './CredentialVault';
import { shell } from 'electron';
import { OAuth2Client } from 'google-auth-library';
import { google } from 'googleapis';
import { oauthServer } from '../utils/oauth-server';
import type { AuthState } from '../types';

const SERVICE = 'YouTubeUploader';
const CLIENT_ID = 'GoogleClientId';
const CLIENT_SECRET = 'GoogleClientSecret';
const TOKENS = 'GoogleTokens';

export class AuthService {
  constructor(private readonly db: Database.Database, private readonly vault: CredentialVault) {}

  async hasCredentials(): Promise<boolean> {
    return Boolean(await this.vault.get(SERVICE, CLIENT_ID) && await this.vault.get(SERVICE, CLIENT_SECRET));
  }

  async saveCredentials(id: string, secret: string): Promise<void> {
    if (!id.trim() || !secret.trim()) throw new Error('Client ID and secret are required');
    if (!/\.apps\.googleusercontent\.com$/i.test(id.trim())) throw new Error('This does not look like a Google OAuth client ID');
    await Promise.all([this.vault.set(SERVICE, CLIENT_ID, id.trim()), this.vault.set(SERVICE, CLIENT_SECRET, secret.trim())]);
  }

  private async client(redirect?: string): Promise<OAuth2Client> {
    const [id, secret] = await Promise.all([this.vault.get(SERVICE, CLIENT_ID), this.vault.get(SERVICE, CLIENT_SECRET)]);
    if (!id || !secret) throw new Error('Google OAuth credentials have not been configured');
    const client = new OAuth2Client(id, secret, redirect);
    const raw = await this.vault.get(SERVICE, TOKENS);
    if (raw) client.setCredentials(JSON.parse(raw) as Parameters<OAuth2Client['setCredentials']>[0]);
    client.on('tokens', (tokens) => { void this.vault.set(SERVICE, TOKENS, JSON.stringify({ ...client.credentials, ...tokens })); });
    return client;
  }

  async state(): Promise<AuthState> {
    const hasCredentials = await this.hasCredentials();
    let row = this.db.prepare('SELECT email,name,picture_url,channel_id,channel_name FROM user_account WHERE id=1').get() as { email: string; name: string; picture_url?: string; channel_id?: string; channel_name?: string } | undefined;
    let token = await this.vault.get(SERVICE, TOKENS);
    if (token && row) {
      try { await (await this.client()).getAccessToken(); }
      catch (error:unknown) { const message=error instanceof Error?error.message:String(error);if(/invalid_grant|unauthorized|revoked/i.test(message)){await this.vault.delete(SERVICE, TOKENS);this.db.prepare('DELETE FROM user_account').run();token=null;row=undefined;} }
    }
    return { authenticated: Boolean(token && row), hasCredentials, account: row ? { email: row.email, name: row.name, pictureUrl: row.picture_url, channelId: row.channel_id, channelName: row.channel_name } : undefined };
  }

  async login(): Promise<AuthState> {
    const server = await oauthServer();
    try {
      const client = await this.client(server.redirectUri);
      const url = client.generateAuthUrl({ access_type: 'offline', prompt: 'consent', scope: [
        'https://www.googleapis.com/auth/youtube.upload', 'https://www.googleapis.com/auth/youtube.readonly',
        'https://www.googleapis.com/auth/youtube.force-ssl', 'openid', 'email', 'profile'
      ] });
      await shell.openExternal(url);
      const code = await server.code;
      const result = await client.getToken(code);
      client.setCredentials(result.tokens);
      await this.vault.set(SERVICE, TOKENS, JSON.stringify(result.tokens));
      const oauth = google.oauth2({ version: 'v2', auth: client });
      const youtube = google.youtube({ version: 'v3', auth: client });
      const [user, channels] = await Promise.all([oauth.userinfo.get(), youtube.channels.list({ part: ['snippet', 'statistics'], mine: true })]);
      const channel = channels.data.items?.[0];
      this.db.prepare('INSERT INTO user_account(id,google_id,email,name,picture_url,channel_id,channel_name,channel_thumb,subscriber_count,video_count) VALUES(1,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET google_id=excluded.google_id,email=excluded.email,name=excluded.name,picture_url=excluded.picture_url,channel_id=excluded.channel_id,channel_name=excluded.channel_name,channel_thumb=excluded.channel_thumb,subscriber_count=excluded.subscriber_count,video_count=excluded.video_count')
        .run(user.data.id, user.data.email, user.data.name, user.data.picture, channel?.id, channel?.snippet?.title, channel?.snippet?.thumbnails?.default?.url, Number(channel?.statistics?.subscriberCount ?? 0), Number(channel?.statistics?.videoCount ?? 0));
      return await this.state();
    } finally { server.close(); }
  }

  async authenticatedClient(): Promise<OAuth2Client> {
    const client = await this.client(); await client.getAccessToken(); return client;
  }

  async logout(): Promise<void> {
    try { const client = await this.client(); await client.revokeCredentials(); } catch { /* Revocation is best effort. */ }
    await this.vault.delete(SERVICE, TOKENS); this.db.prepare('DELETE FROM user_account').run();
  }
}
