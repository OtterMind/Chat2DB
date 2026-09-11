import * as monaco from 'monaco-editor';

// Frontend routing capability: these databases consume backend completion results
// directly, so local snippets/providers should stay out of the completion list.
const backendCompletionModels = new WeakSet<monaco.editor.ITextModel>();

export function setBackendCompletionModel(
  model: monaco.editor.ITextModel | null | undefined,
  enabled: boolean,
): void {
  if (!model) {
    return;
  }
  if (enabled) {
    backendCompletionModels.add(model);
    return;
  }
  backendCompletionModels.delete(model);
}

export function isBackendCompletionModel(model: monaco.editor.ITextModel | null | undefined): boolean {
  return !!model && backendCompletionModels.has(model);
}
