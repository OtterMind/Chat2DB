import type { AnswerVO } from '@/typings/chat';

export function updateFirstAnswer(
  answers: AnswerVO[] | undefined,
  params: Partial<AnswerVO>,
): AnswerVO[] | undefined {
  if (!answers?.length) {
    return answers;
  }

  const [first, ...remaining] = answers;
  return [{ ...first, ...params }, ...remaining];
}
