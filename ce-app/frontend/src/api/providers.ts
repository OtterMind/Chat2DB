import api from './client'

export interface ProviderRow {
  id: string
  name: string
  version?: string
  entry?: string
  capabilities: string[]
  licence: string
  description?: string
  enabled: boolean
  /** Why a folder is not a provider — empty when it is one. */
  problems: string[]
  folder: string
  status?: { ok: boolean; note: string } | null
}

export interface ProviderShelf {
  dir: string
  appVersion: string
  capabilities: { id: string; contract: string }[]
  protocol: string[]
  licenceRule: string
  providers: ProviderRow[]
  count: number
}

export const providersApi = {
  list: async (): Promise<ProviderShelf> => (await api.get('/providers')).data,
  enable: async (id: string, enabled: boolean): Promise<{ id: string; enabled: boolean }> =>
    (await api.post('/providers/enable', { id, enabled })).data,
  test: async (id: string): Promise<{ ok: boolean; note: string }> =>
    (await api.post('/providers/test', { id }, { timeout: 30_000 })).data,
}
