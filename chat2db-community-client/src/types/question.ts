export interface QuestionOption { id: string; label: string; description?: string | null }
export interface QuestionResponse { optionId?: string; text?: string }
export interface QuestionAnswer extends QuestionResponse { questionId: string; optionLabel?: string }
export type QuestionStatus = 'pending' | 'answered' | 'closed';
