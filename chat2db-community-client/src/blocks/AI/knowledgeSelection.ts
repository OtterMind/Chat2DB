import type { ISelectedKnowledge, KnowledgeSelectionType } from '@/service/aiStream';

export interface IKnowledgeSelectionReference {
  id: number;
  type: KnowledgeSelectionType;
}

export const toKnowledgeSelectionReferences = (
  selectedKnowledge: readonly ISelectedKnowledge[] | undefined,
): IKnowledgeSelectionReference[] =>
  (selectedKnowledge || []).map(({ id, type }) => ({
    id,
    type,
  }));
