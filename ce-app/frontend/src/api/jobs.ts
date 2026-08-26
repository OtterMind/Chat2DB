import api from './client'

export interface Job {
  id: string; name: string; source_url: string | null; source_type: string
  status: string; current_stage: string | null; progress: number
  error: string | null; created_at: string; updated_at: string
}
export interface JobCreate {
  name: string; source_url: string; source_type: string; config?: Record<string, unknown>
}
export interface Clip {
  id: string; job_id: string; start_time: number; end_time: number; score: number
  ai_reasoning: string | null; status: string; output_path: string | null
  thumbnail_path: string | null; created_at: string
}

export const jobsApi = {
  create: async (data: JobCreate): Promise<Job> => (await api.post('/jobs', data)).data,
  list: async (page = 1, perPage = 20): Promise<{ jobs: Job[]; total: number }> =>
    (await api.get('/jobs', { params: { page, per_page: perPage } })).data,
  get: async (id: string): Promise<Job> => (await api.get(`/jobs/${id}`)).data,
  remove: async (id: string): Promise<void> => { await api.delete(`/jobs/${id}`) },
  start: async (id: string): Promise<Job> => (await api.post(`/jobs/${id}/start`)).data,
  cancel: async (id: string): Promise<Job> => (await api.post(`/jobs/${id}/cancel`)).data,
  retry: async (id: string): Promise<Job> => (await api.post(`/jobs/${id}/retry`)).data,
  clips: async (id: string): Promise<Clip[]> => (await api.get(`/jobs/${id}/clips`)).data,
}

export const systemApi = {
  doctor: async () => (await api.get('/system/doctor')).data,
  info: async () => (await api.get('/system/info')).data,
  settings: async () => (await api.get('/system/settings')).data,
  attribution: async (): Promise<{
    backend: { name: string; version: string; licence: string; role: string }[]
    bundled: { name: string; version: string; licence: string; role: string; why?: string }[]
  }> => (await api.get('/system/attribution')).data,
  updateSettings: async (settings: Record<string, unknown>) =>
    (await api.put('/system/settings', settings)).data,
}