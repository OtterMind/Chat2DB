import { create } from 'zustand'

export type TaskKind = 'job' | 'upload' | 'export' | 'download'

export interface BackgroundTask {
  id: string
  kind: TaskKind
  /** Persian label shown in the task dock. */
  label: string
  stage?: string
  progress: number
  status: 'running' | 'done' | 'failed' | 'queued'
  error?: string
  route?: string
  startedAt: number
  updatedAt: number
}

interface RuntimeState {
  /** Every long-running operation, keyed by id. Lives outside React tree so
   *  navigating between tabs never cancels or forgets it. */
  tasks: Record<string, BackgroundTask>
  wsConnected: boolean
  lastEventAt: number | null
  /** null while unknown, then the live reachability of the local API. */
  backendOnline: boolean | null
  backendCheckedAt: number | null

  upsertTask: (task: Partial<BackgroundTask> & { id: string; kind: TaskKind; label: string }) => void
  patchTask: (id: string, patch: Partial<BackgroundTask>) => void
  removeTask: (id: string) => void
  clearFinished: () => void
  setWsConnected: (connected: boolean) => void
  setBackendOnline: (online: boolean) => void
}

export const useRuntime = create<RuntimeState>((set) => ({
  tasks: {},
  wsConnected: false,
  lastEventAt: null,
  backendOnline: null,
  backendCheckedAt: null,

  upsertTask: (task) =>
    set((state) => {
      const now = Date.now()
      const existing = state.tasks[task.id]
      return {
        tasks: {
          ...state.tasks,
          [task.id]: {
            ...{ progress: 0, status: 'running' as const },
            ...existing,
            ...task,
            startedAt: existing?.startedAt ?? now,
            updatedAt: now,
          } as BackgroundTask,
        },
      }
    }),

  patchTask: (id, patch) =>
    set((state) => {
      const existing = state.tasks[id]
      if (!existing) return state
      return {
        tasks: { ...state.tasks, [id]: { ...existing, ...patch, updatedAt: Date.now() } },
        lastEventAt: Date.now(),
      }
    }),

  removeTask: (id) =>
    set((state) => {
      const { [id]: _removed, ...rest } = state.tasks
      return { tasks: rest }
    }),

  clearFinished: () =>
    set((state) => ({
      tasks: Object.fromEntries(
        Object.entries(state.tasks).filter(([, t]) => t.status === 'running' || t.status === 'queued')
      ),
    })),

  setWsConnected: (wsConnected) => set({ wsConnected }),
  setBackendOnline: (backendOnline) => set({ backendOnline, backendCheckedAt: Date.now() }),
}))

/** Selector helpers */
export const selectActiveTasks = (s: RuntimeState) =>
  Object.values(s.tasks)
    .filter((t) => t.status === 'running' || t.status === 'queued')
    .sort((a, b) => b.startedAt - a.startedAt)

export const selectAllTasks = (s: RuntimeState) =>
  Object.values(s.tasks).sort((a, b) => b.startedAt - a.startedAt)
