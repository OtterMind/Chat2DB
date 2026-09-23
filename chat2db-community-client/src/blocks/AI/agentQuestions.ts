import type { AgentEvent } from '@/service/agent';
import type { QuestionAnswer, QuestionOption, QuestionStatus } from '@/types/question';
import { isTerminalAgentEvent } from './agentEvents';

export interface AgentQuestionItem {
  id: string;
  sessionId: string;
  runId: string;
  question: string;
  options: QuestionOption[];
  status: QuestionStatus;
  answer?: QuestionAnswer;
}

const isOption = (value: unknown): value is QuestionOption => !!value && typeof value === 'object'
  && 'id' in value && typeof value.id === 'string' && 'label' in value && typeof value.label === 'string'
  && (!('description' in value) || value.description == null || typeof value.description === 'string');

export const updateAgentQuestions = (current: AgentQuestionItem[], events: AgentEvent[]): AgentQuestionItem[] => {
  const questions = new Map(current.map((item) => [item.id, item]));
  for (const event of events) {
    const { questionId, question, options, answer } = event.payload;
    if (event.type === 'QUESTION_REQUESTED' && typeof questionId === 'string' && typeof question === 'string'
        && event.runId && Array.isArray(options) && options.every(isOption) && !questions.has(questionId)) {
      questions.set(questionId, { id: questionId, sessionId: event.sessionId, runId: event.runId,
        question, options, status: 'pending' });
    }
    const item = typeof questionId === 'string' ? questions.get(questionId) : undefined;
    if (item && item.sessionId === event.sessionId && item.runId === event.runId) {
      if (event.type === 'QUESTION_ANSWERED' && answer && typeof answer === 'object') {
        const value = answer as Record<string, unknown>;
        questions.set(item.id, { ...item, status: 'answered', answer: {
          questionId: item.id,
          optionId: typeof value.optionId === 'string' ? value.optionId : undefined,
          optionLabel: typeof value.optionLabel === 'string' ? value.optionLabel : undefined,
          text: typeof value.text === 'string' ? value.text : undefined,
        } });
      }
      if (event.type === 'QUESTION_CLOSED' && item.status === 'pending') questions.set(item.id, { ...item, status: 'closed' });
    }
    if (isTerminalAgentEvent(event)) {
      for (const [id, pending] of questions) {
        if (pending.sessionId === event.sessionId && pending.runId === event.runId && pending.status === 'pending') {
          questions.set(id, { ...pending, status: 'closed' });
        }
      }
    }
  }
  return [...questions.values()];
};
