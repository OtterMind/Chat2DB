import { useGlobalStore } from '@/store/global';
import { useEffect } from 'react';
import { handelCreateConsole } from '@/pages/main/workspace/functions/shortcutKeyCreateConsole';
import { useWorkspaceStore } from '@/store/workspace';
import {
  ShortcutAction,
  ShortcutOverrides,
  ShortcutScope,
  getEffectiveShortcutConfigMap,
  isShortcutEventMatch,
} from '@/constants/shortcut';
import { useAIStore } from '@/store/ai';
import { requestCloseActiveResultTab } from '@/service/resultTabShortcut';
import { handleWebFrameZoom, WebFrameZoomType } from './jcefZoom';
import { prepareGlobalShortcutHandling } from './shortcutDispatch';
import { AppTitleBarAction, requestAppTitleBarAction } from './appTitleBarAction';

const NON_TEXT_INPUT_TYPES = new Set([
  'button',
  'checkbox',
  'color',
  'file',
  'hidden',
  'image',
  'radio',
  'range',
  'reset',
  'submit',
]);

function isEditableElement(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }

  const editable = target.closest('input, textarea, [contenteditable="true"], [contenteditable=""]');
  if (!(editable instanceof HTMLElement)) {
    return false;
  }

  if (editable instanceof HTMLInputElement) {
    return !editable.disabled && !editable.readOnly && !NON_TEXT_INPUT_TYPES.has(editable.type);
  }

  if (editable instanceof HTMLTextAreaElement) {
    return !editable.disabled && !editable.readOnly;
  }

  return editable.isContentEditable;
}

class ShortcutManager {
  private static instance: ShortcutManager;
  private cleanup: (() => void) | null = null;

  private constructor() {}

  public static getInstance(): ShortcutManager {
    if (!ShortcutManager.instance) {
      ShortcutManager.instance = new ShortcutManager();
    }
    return ShortcutManager.instance;
  }

  private handleZoom(type: WebFrameZoomType): void {
    void handleWebFrameZoom(type);
  }

  private handleSwitchToNav(nav: 'workspace' | 'dashboard' | 'stream' | 'setting'): void {
    const titleBarAction: AppTitleBarAction = nav === 'setting' ? 'settings' : nav;
    if (requestAppTitleBarAction(titleBarAction)) {
      return;
    }

    const { setMainPageActiveTab, setSettingPageActiveTab } = useGlobalStore.getState();
    if (nav === 'setting') {
      setSettingPageActiveTab('basic');
    } else {
      setMainPageActiveTab({ page: nav });
      setSettingPageActiveTab(false);
    }
  }

  private handleActionConsole(action: 'delete' | 'create'): void {
    const { mainPageActiveTab, setMainPageActiveTab, setSettingPageActiveTab } = useGlobalStore.getState();
    const { deleteActiveWorkspaceTab } = useWorkspaceStore.getState();
    if (action === 'delete') {
      if (requestCloseActiveResultTab()) {
        return;
      }
      if (mainPageActiveTab !== 'workspace') {
        setMainPageActiveTab({ page: 'workspace' });
        setSettingPageActiveTab(false);
      }
      deleteActiveWorkspaceTab();
      return;
    }

    if (mainPageActiveTab !== 'workspace') {
      setMainPageActiveTab({ page: 'workspace' });
      setSettingPageActiveTab(false);
    }

    handelCreateConsole();
  }

  private handleArouseAIAssistant(): void {
    const { showPanel, setShowPanel } = useAIStore.getState();
    setShowPanel(!showPanel);
  }

  private handleNewAIChat(): void {
    const { setShowPanel } = useAIStore.getState();
    setShowPanel(true);
    window.dispatchEvent(new CustomEvent('stream:newChat'));
  }

  private handleShortcut(action: ShortcutAction): void {
    switch (action) {
      case ShortcutAction.OpenSetting:
        this.handleSwitchToNav('setting');
        break;
      case ShortcutAction.ZoomIn:
        this.handleZoom('in');
        break;
      case ShortcutAction.ZoomOut:
        this.handleZoom('out');
        break;
      case ShortcutAction.ZoomReset:
        this.handleZoom('reset');
        break;
      case ShortcutAction.SwitchToWorkspace:
        this.handleSwitchToNav('workspace');
        break;
      case ShortcutAction.SwitchToDashboard:
        this.handleSwitchToNav('dashboard');
        break;
      case ShortcutAction.SwitchToChat:
        this.handleSwitchToNav('stream');
        break;
      case ShortcutAction.CloseCurrentConsole:
        this.handleActionConsole('delete');
        break;
      case ShortcutAction.NewConsole:
        this.handleActionConsole('create');
        break;
      case ShortcutAction.ArouseAIAssistant:
        this.handleArouseAIAssistant();
        break;
      case ShortcutAction.NewAIChat:
        this.handleNewAIChat();
        break;
      default:
        console.warn(`Unknown shortcut action: ${action}`);
        break;
    }
  }

  private handleKeyDown = (e: KeyboardEvent): void => {
    const isFromEditable = isEditableElement(e.target);

    const { shortcutOverrides } = useGlobalStore.getState();
    const shortcutConfig = getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides);

    const matchedConfig = Object.values(shortcutConfig).find((config) => {
      return config.scope === ShortcutScope.Global && !config.disabled && isShortcutEventMatch(e, config.binding);
    });

    if (!matchedConfig) {
      return;
    }

    if (isFromEditable && !matchedConfig.allowInEditable) {
      return;
    }

    const action = matchedConfig.action as ShortcutAction;

    if (!prepareGlobalShortcutHandling(e, action)) {
      return;
    }

    this.handleShortcut(action);
  };

  public start(): void {
    if (this.cleanup) {
      this.cleanup();
    }
    window.addEventListener('keydown', this.handleKeyDown, true);
    this.cleanup = () => window.removeEventListener('keydown', this.handleKeyDown, true);
  }

  public stop(): void {
    if (this.cleanup) {
      this.cleanup();
      this.cleanup = null;
    }
  }
}

// React Hook wrapper
export const useShortcutManager = (): void => {
  useEffect(() => {
    const manager = ShortcutManager.getInstance();
    manager.start();
    return () => manager.stop();
  }, []);
};
