import React, { useEffect, useRef, useCallback } from 'react';
import * as monaco from 'monaco-editor';
import { v4 as uuid } from 'uuid';
import { MonacoEditorRef } from '../editor/MonacoEditor';
import AppTheme from '@/components/AppTheme';
import BuiltInCopilot from '../components/BuiltInCopilot';
import ReactDOM from 'react-dom/client';
import PlaceholderContentWidget from '../components/AIPlaceholder/PlaceholderContentWidget';
import { AIState } from '@/store/workspace/slices/ai/initialState';
import { useGlobalStore } from '@/store/global';

interface UseCopilotProps {
  editorRef: React.RefObject<MonacoEditorRef>;
  placeholderContentWidget: PlaceholderContentWidget | null;
  canAI: boolean;
  aiInputParams: AIState['consoleAiInputParams'] | false;
  active: boolean;
}

const isTextSelectionSupportedInput = (element: HTMLElement): element is HTMLInputElement | HTMLTextAreaElement => {
  if (element instanceof HTMLTextAreaElement) {
    return true;
  }
  if (!(element instanceof HTMLInputElement)) {
    return false;
  }
  return ['text', 'search', 'url', 'tel', 'password', 'email'].includes(element.type);
};

const findFocusableCopilotInput = (overlayDom: HTMLElement) => {
  const candidates = Array.from(
    overlayDom.querySelectorAll<HTMLElement>('textarea, input:not([type="file"]), [contenteditable="true"]'),
  );

  return (
    candidates.find((element) => {
      if (element instanceof HTMLInputElement) {
        return element.type !== 'hidden' && !element.disabled;
      }
      if (element instanceof HTMLTextAreaElement) {
        return !element.disabled;
      }
      return element.getAttribute('contenteditable') === 'true';
    }) || null
  );
};

const useCopilot = ({ editorRef, placeholderContentWidget, canAI, aiInputParams, active }: UseCopilotProps) => {
  const overlayDomRef = useRef<{
    newOverlayDom: any;
    destroyCopilot: any;
  }>();
  const editorZoneIdRef = useRef<string | null>(null);
  const overlayWidgetRef = useRef<monaco.editor.IOverlayWidget>();
  const globalEditorSettings = useGlobalStore((s) => s.editorSettings);

  const focusCopilotInput = useCallback((retryCount = 0) => {
    const overlayDom = overlayDomRef.current?.newOverlayDom as HTMLElement | undefined;
    if (!overlayDom) return;

    const input = findFocusableCopilotInput(overlayDom);
    if (input) {
      input.focus();
      if (isTextSelectionSupportedInput(input)) {
        const textInput = input;
        const length = textInput.value.length;
        textInput.setSelectionRange(length, length);
      }
      return;
    }

    if (retryCount < 5) {
      window.setTimeout(() => focusCopilotInput(retryCount + 1), 16);
    }
  }, []);

  // ------ Clear Copilot when canAI or active changes ------
  useEffect(() => {
    if (!canAI || !active) {
      clearCopilot();
      return;
    }

    if (aiInputParams) {
      showCopilot();
    }
  }, [canAI, active, aiInputParams]);

  useEffect(() => {
    return () => {
      clearCopilot();
    };
  }, []);

  // ------ Listen for a slash to invoke AI ------
  useEffect(() => {
    console.log('[DEBUG:useCopilot] Setting up slash listener', { canAI, active, hasEditor: !!editorRef.current });
    if (!canAI || !active || !editorRef.current) return;

    const editorIns = editorRef.current?.getInstance() ?? null;
    if (!editorIns) return;

    const disposables = [
      editorIns.addAction({
        id: 'slash-insertion',
        label: 'slash',
        keybindings: [monaco.KeyCode.Slash],
        run: (ed) => {
          console.log('[DEBUG:Keyboard] Slash key action triggered');
          const position = ed.getPosition();
          if (!position) return;

          // Get the current line content.
          const lineContent = ed.getModel()?.getLineContent(position.lineNumber) || '';

          // Check whether the line is empty or the cursor is at the start.
          const isEmptyOrStart = lineContent.trim() === '' || position.column === 1;

          console.log('[DEBUG:Keyboard] Slash key context', {
            lineContent,
            isEmptyOrStart,
            position
          });

          if (isEmptyOrStart) {
            console.log('[DEBUG:Keyboard] Triggering showCopilot from slash action');
            showCopilot();
          } else {
            console.log('[DEBUG:Keyboard] Typing slash character');
            ed.trigger('keyboard', 'type', { text: '/' });
          }
        },
      }),
      editorIns.onDidChangeModelContent(() => {
        const position = editorIns.getPosition();
        if (!position) return;

        const model = editorIns.getModel();
        if (!model) return;

        // Get the current line content.
        const lineContent = model.getLineContent(position.lineNumber);

        // Handle a slash just entered at the start of the line.
        if (/^[/／]$/.test(lineContent) && position.column === 2) {
          console.log('[DEBUG:Keyboard] Slash detected at line start, showing Copilot');
          // Remove the entered slash.
          editorIns.executeEdits('', [
            {
              range: new monaco.Range(position.lineNumber, 1, position.lineNumber, 2),
              text: '',
            },
          ]);
          showCopilot();
        }
      }),
    ];

    console.log('[DEBUG:useCopilot] Slash listener registered');
    return () => {
      console.log('[DEBUG:useCopilot] Disposing slash listener');
      disposables.forEach((disposable) => disposable.dispose());
    };
  }, [canAI, active, editorRef, placeholderContentWidget]);

  // ------ Clear Copilot ------
  const clearCopilot = () => {
    console.log('[DEBUG:Copilot] clearCopilot called', {
      hasOverlayDom: !!overlayDomRef.current,
      hasZoneId: !!editorZoneIdRef.current,
      hasOverlayWidget: !!overlayWidgetRef.current
    });

    // Clean up the DOM element and React root.
    const _overlayDom = overlayDomRef.current;
    if (_overlayDom) {
      _overlayDom.destroyCopilot();
      console.log('[DEBUG:Copilot] BuiltInCopilot DOM destroyed');
    }
    overlayDomRef.current = undefined;

    const editorIns = editorRef.current?.getInstance() ?? null;
    if (!editorIns) {
      console.log('[DEBUG:Copilot] No editor instance, returning');
      return;
    }

    try {
      // Clean up the view zone.
      const currentZoneId = editorZoneIdRef.current;
      if (currentZoneId) {
        editorIns.changeViewZones((changeAccessor) => {
          changeAccessor.removeZone(currentZoneId);
        });
        console.log('[DEBUG:Copilot] ViewZone removed');
      }

      // Clean up the overlay widget.
      if (overlayWidgetRef.current) {
        editorIns.removeOverlayWidget(overlayWidgetRef.current);
        overlayWidgetRef.current = undefined;
        console.log('[DEBUG:Copilot] OverlayWidget removed');
      }

      // Reset the editor state.
      console.log('[DEBUG:Copilot] Restoring editor focus');
      editorIns.focus();
      editorIns.layout();
      console.log('[DEBUG:Focus] Editor focus restored after clearCopilot', {
        activeElement: document.activeElement
      });
    } catch (error) {
      console.error('[DEBUG:Copilot] Error during clearCopilot:', error);
    }

    // Reset the state.
    editorZoneIdRef.current = null;
    console.log('[DEBUG:Copilot] clearCopilot completed');
  };

  const handleResize = (height: number) => {
    const editorIns = editorRef.current?.getInstance() ?? null;
    if (!editorIns) return;

    const currentZoneId = editorZoneIdRef.current;
    if (!currentZoneId) return;

    editorIns.changeViewZones((changeAccessor) => {
      changeAccessor.layoutZone(currentZoneId);
      changeAccessor.removeZone(currentZoneId);
      editorZoneIdRef.current = createEditorZone(changeAccessor, editorIns.getPosition()?.lineNumber || 1, height);
    });
  };

  // ------ Create Copilot ------
  const createCopilot = useCallback(() => {
    console.log('[DEBUG:Copilot] createCopilot called', { canAI, active });
    if (!canAI || !active) return;

    const editor = editorRef.current?.getInstance();
    if (!editor) {
      console.log('[DEBUG:Copilot] No editor instance');
      return;
    }

    const newOverlayDom = document.createElement('div');
    const overlayId = `overlayId`;
    newOverlayDom.id = overlayId;

    // Set the right margin based on whether the minimap is enabled.
    const minimapEnabled = editor.getOption(monaco.editor.EditorOption.minimap).enabled;
    const rightMargin = minimapEnabled ? '108px' : '28px'; // The minimap is approximately 80 px wide by default.
    newOverlayDom.style.cssText = `left: 55px; right: ${rightMargin};`;

    console.log('[DEBUG:Copilot] Creating BuiltInCopilot component');
    // Create and render the BuiltInCopilot component.
    const root = ReactDOM.createRoot(newOverlayDom);
    const component = (
      <AppTheme>
        <BuiltInCopilot onResize={handleResize} handleEsc={clearCopilot} />
      </AppTheme>
    );
    root.render(component);
    console.log('[DEBUG:Copilot] BuiltInCopilot component rendered');

    // Define an unmount method for cleanup.
    const destroyCopilot = () => {
      root.unmount(); // Unmount the component.
      editor.removeOverlayWidget(overlayWidget); // Remove the overlay widget.
    };

    // Create the overlay widget.
    const overlayWidget = {
      getId: () => 'overlay.zone.widget',
      getDomNode: () => newOverlayDom,
      getPosition: () => null,
    };

    // Add the overlay widget.
    editor.addOverlayWidget(overlayWidget);
    overlayWidgetRef.current = overlayWidget;

    // Return the new DOM element and cleanup method.
    return { newOverlayDom, destroyCopilot };
  }, [canAI, active, clearCopilot]);

  const createEditorZone = (
    changeAccessor: monaco.editor.IViewZoneChangeAccessor,
    lineNumber: number,
    height: number,
  ) => {
    const zoneNode = document.createElement('div');
    zoneNode.id = uuid();

    let heightInLines = height / 20;

    try {
      const editorLineHeight = globalEditorSettings.fontSize * globalEditorSettings.lineHeight;
      heightInLines = height / editorLineHeight;
    } catch (e) {
      console.error(e);
    }

    return changeAccessor.addZone({
      afterLineNumber: lineNumber - 1,
      heightInLines,
      domNode: zoneNode,
      onDomNodeTop: (top) => {
        if (overlayDomRef.current) {
          overlayDomRef.current.newOverlayDom.style.top = top + 'px';
        }
      },
    });
  };

  const showCopilot = ({ height = 40, clear = true } = {}) => {
    console.log('[DEBUG:Copilot] showCopilot called', {
      canAI,
      active,
      height,
      clear,
      existingZoneId: !!editorZoneIdRef.current,
      existingOverlay: !!overlayDomRef.current,
      activeElement: document.activeElement
    });

    if (!canAI || !active) return;

    placeholderContentWidget?.dispose();

    const editorIns = editorRef.current?.getInstance() ?? null;
    if (!editorIns) {
      console.log('[DEBUG:Copilot] No editor instance');
      return;
    }

    const position = editorIns.getPosition();
    const lineNumber = position ? position.lineNumber : 1;
    console.log('[DEBUG:Copilot] Current position:', { lineNumber, position });

    // Reposition Copilot when it already exists.
    if (editorZoneIdRef.current && overlayDomRef.current) {
      console.log('[DEBUG:Copilot] Copilot already exists, repositioning');
      editorIns.changeViewZones((changeAccessor) => {
        changeAccessor.layoutZone(editorZoneIdRef.current!);
        changeAccessor.removeZone(editorZoneIdRef.current!);
        editorZoneIdRef.current = createEditorZone(changeAccessor, lineNumber, height);
      });
    } else {
      // Create Copilot for the first time.
      console.log('[DEBUG:Copilot] Creating new copilot');
      if (clear) {
        clearCopilot();
        overlayDomRef.current = createCopilot();
      }

      editorIns.changeViewZones((changeAccessor) => {
        editorZoneIdRef.current = createEditorZone(changeAccessor, lineNumber, height);
      });
    }

    console.log('[DEBUG:Copilot] showCopilot completed', {
      activeElement: document.activeElement,
      zoneId: editorZoneIdRef.current
    });

    focusCopilotInput();
  };
};

export default useCopilot;
