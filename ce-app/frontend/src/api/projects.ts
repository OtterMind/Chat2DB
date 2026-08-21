import api from './client'

export interface ProjectSummary {
  name: string
  path: string
  updatedAt: number
  clips: number
  duration: number
  missingMedia: string[]
  broken: boolean
}

export interface ProjectDocument {
  format: number
  name: string
  updatedAt: number
  timeline: { tracks: unknown[]; clips: unknown[]; transitions: unknown[] }
  view?: Record<string, unknown>
  missingMedia?: string[]
}

export const projectsApi = {
  list: async (): Promise<{ projects: ProjectSummary[]; hasAutosave: boolean }> =>
    (await api.get('/projects')).data,
  save: async (name: string, timeline: unknown, view?: unknown): Promise<ProjectSummary> =>
    (await api.post('/projects', { name, timeline, view })).data,
  load: async (name: string): Promise<ProjectDocument> =>
    (await api.get(`/projects/${encodeURIComponent(name)}`)).data,
  remove: async (name: string): Promise<void> => {
    await api.delete(`/projects/${encodeURIComponent(name)}`)
  },
  autosave: async (name: string, timeline: unknown, view?: unknown): Promise<void> => {
    await api.post('/projects/autosave', { name, timeline, view })
  },
  loadAutosave: async (): Promise<ProjectDocument> => (await api.get('/projects/autosave')).data,
}
