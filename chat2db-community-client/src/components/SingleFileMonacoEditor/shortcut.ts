interface SuggestController {
  _widget?: {
    suggestWidgetVisible?: {
      get?: () => boolean;
    };
  };
}

export interface SuggestEditor {
  getContribution?: (id: string) => SuggestController | null | undefined;
}

interface KeydownTarget {
  addEventListener: (type: 'keydown', listener: (event: KeyboardEvent) => void) => void;
  removeEventListener: (type: 'keydown', listener: (event: KeyboardEvent) => void) => void;
}

export function isSuggestWidgetVisible(editor: SuggestEditor | null | undefined) {
  const controller = editor?.getContribution?.('editor.contrib.suggestController');
  return controller?._widget?.suggestWidgetVisible?.get?.() === true;
}

export function createSingleFileShortcutController(target: KeydownTarget, onEnter: () => void) {
  let editor: SuggestEditor | null = null;

  const handleKeydown = (event: KeyboardEvent) => {
    if (event.key !== 'Enter' || !editor || isSuggestWidgetVisible(editor)) {
      return;
    }
    event.preventDefault();
    onEnter();
  };

  const deactivate = () => {
    target.removeEventListener('keydown', handleKeydown);
    editor = null;
  };

  return {
    activate(nextEditor: SuggestEditor) {
      target.removeEventListener('keydown', handleKeydown);
      editor = nextEditor;
      target.addEventListener('keydown', handleKeydown);
    },
    deactivate,
    dispose: deactivate,
  };
}
