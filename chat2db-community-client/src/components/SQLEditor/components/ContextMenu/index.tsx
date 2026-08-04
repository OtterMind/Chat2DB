import React, { CSSProperties, memo, useEffect, useMemo } from 'react';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { useStyles } from './style';
import * as monaco from 'monaco-editor';
import { MonacoEditorRef } from '../../editor/MonacoEditor';
import { SQLOptType, EditorType } from '../../type';
import i18n, { en_US } from '@/i18n';
import ShortcutMenuLabel from '@/components/ShortcutMenuLabel';
import { WorkspaceTabType } from '@/constants';
import { useGlobalStore } from '@/store/global';
import { isTemporaryId } from '@/utils';
import {
  ShortcutAction,
  ShortcutOverrides,
  getEffectiveShortcutConfigMap,
  shortcutBindingToMonacoKeybinding,
} from '@/constants/shortcut';

export interface IMenuItem {
  icon?: string;
  key: SQLOptType;
  label: keyof typeof en_US;
  shortcutAction?: ShortcutAction;
  keybindings?: number[] | null;
}

type MenuEntry = IMenuItem | { isSeparator: true };
const SQL_EDITOR_KEYBINDING_CONTEXT = 'editorTextFocus';

interface IProps {
  id: string;
  dbInfo?: { consoleId?: number | string };
  editorRef: React.MutableRefObject<MonacoEditorRef | null>;
  type?: EditorType;
  canEditTable?: boolean;
  sqlActionEnabled?: boolean;
  contentDiffEnabled?: boolean;
  onCloseContextMenu: () => void;
  onClick?: (key: SQLOptType, content: string) => void;
  config: {
    open: boolean;
    context: string;
    position: CSSProperties;
  };
}

const createMenuItem = (
  key: SQLOptType,
  label: keyof typeof en_US,
  shortcutAction?: ShortcutAction,
): IMenuItem => ({
  key,
  label,
  shortcutAction,
});

const ContextMenu = memo((props: IProps) => {
  const {
    id,
    dbInfo,
    editorRef,
    onCloseContextMenu,
    config,
    onClick,
    type,
    canEditTable,
    sqlActionEnabled = true,
    contentDiffEnabled = false,
  } = props;
  const { open, context, position } = config;
  const { styles } = useStyles();
  const shortcutOverrides = useGlobalStore((s) => s.shortcutOverrides);
  const shortcutConfig = useMemo(
    () => getEffectiveShortcutConfigMap(shortcutOverrides as ShortcutOverrides),
    [shortcutOverrides],
  );

  const menus = useMemo<MenuEntry[]>(
    () => {
      const isLocalFile = type === WorkspaceTabType.LocalSQLFile;
      const canSaveConsole =
        type === WorkspaceTabType.CONSOLE &&
        typeof dbInfo?.consoleId === 'number' &&
        !isTemporaryId(dbInfo.consoleId);
      const saveMenu =
        isLocalFile || canSaveConsole
          ? [
              createMenuItem(
                isLocalFile ? SQLOptType.SAVE_FILE : SQLOptType.SAVE_SQL,
                isLocalFile ? 'monaco.text.saveFile' : 'monaco.text.saveSQL',
                ShortcutAction.SqlSave,
              ),
            ]
          : [];

      const editMenus: MenuEntry[] = [
        // createMenuItem(SQLOptType.NL_2_SQL, 'monaco.text.nl2sql'),
        // createMenuItem(SQLOptType.SQL_EXPLAIN, 'monaco.text.sqlExplain'),
        // createMenuItem(SQLOptType.SQL_OPTIMIZER, 'monaco.text.sqlOptimizer'),
        // { isSeparator: true },
        createMenuItem(SQLOptType.COPY, 'monaco.text.copy', ShortcutAction.SqlCopy),
        // createMenuItem(SQLOptType.PASTE, 'monaco.text.paste', [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyV]),
        createMenuItem(SQLOptType.PASTE, 'monaco.text.paste', ShortcutAction.SqlPaste),
        createMenuItem(SQLOptType.CUT, 'monaco.text.cut', ShortcutAction.SqlCut),
        { isSeparator: true },
        ...saveMenu,
        createMenuItem(
          SQLOptType.SAVE_FILE_TO_DESKTOP,
          'monaco.text.saveFileToDesktop',
          ShortcutAction.SqlSaveToDesktop,
        ),
      ];
      const contentDiffMenus: MenuEntry[] = contentDiffEnabled
        ? [{ isSeparator: true }, createMenuItem(SQLOptType.OPEN_CONTENT_DIFF, 'monaco.text.showDiff')]
        : [];

      if (!sqlActionEnabled) {
        return [...editMenus, ...contentDiffMenus];
      }

      return [
        ...editMenus,
        createMenuItem(SQLOptType.PASTE_AS_SQL_IN_VALUES, 'monaco.text.pasteAsSqlInValues'),
        createMenuItem(SQLOptType.CASE_CONVERT, 'monaco.text.caseConvert', ShortcutAction.SqlCaseConvert),
        createMenuItem(SQLOptType.FORMAT_SQL, 'monaco.text.formatSQL', ShortcutAction.SqlFormat),
        createMenuItem(SQLOptType.TOGGLE_LINE_COMMENT, 'monaco.text.toggleLineComment', ShortcutAction.SqlToggleLineComment),
        createMenuItem(SQLOptType.TOGGLE_BLOCK_COMMENT, 'monaco.text.toggleBlockComment', ShortcutAction.SqlToggleBlockComment),
        { isSeparator: true },
        createMenuItem(SQLOptType.EXECUTE_SHORTCUT_SQL, 'monaco.text.executeSingleSQL', ShortcutAction.SqlExecuteCurrent),
        createMenuItem(SQLOptType.EXECUTE_SQL, 'monaco.text.executeSQL', ShortcutAction.SqlExecuteAll),
        ...(canEditTable
          ? ([
              { isSeparator: true },
              createMenuItem(SQLOptType.EDIT_TABLE, 'workspace.menu.editTable'),
            ] as MenuEntry[])
          : []),
        { isSeparator: true },
        createMenuItem(SQLOptType.OPEN_SETTINGS, 'monaco.text.openSettings', ShortcutAction.SqlOpenEditorSetting),
        ...contentDiffMenus,
      ];
    },
    [type, canEditTable, dbInfo?.consoleId, sqlActionEnabled, contentDiffEnabled],
  );

  const menusWithShortcuts = useMemo(
    () =>
      menus.map((menu) => {
        if ('isSeparator' in menu || !menu.shortcutAction) {
          return menu;
        }

        const shortcut = shortcutConfig[menu.shortcutAction];
        const keybinding = shortcutBindingToMonacoKeybinding(shortcut?.binding, monaco);
        return {
          ...menu,
          keybindings: keybinding ? [keybinding] : null,
        };
      }),
    [menus, shortcutConfig],
  );

  useEffect(() => {
    const editor = editorRef?.current?.getInstance();
    const actionDisposers: any[] = [];

    (menusWithShortcuts || []).forEach((menu) => {
      if (!('isSeparator' in menu) && menu.keybindings) {
        const { key, label, keybindings } = menu as IMenuItem;
        const actionDisposer = editor?.addAction({
          id: key,
          label: i18n(label),
          keybindings: keybindings || [],
          keybindingContext: SQL_EDITOR_KEYBINDING_CONTEXT,
          run: () => {
            const content = editorRef?.current?.getSelectedContent() ?? '';
            onClick?.(key, content);
          },
        });
        actionDisposers.push(actionDisposer);
      }
    });
    return () => {
      actionDisposers.forEach((actionDisposer) => actionDisposer.dispose());
    };
  }, [editorRef, menusWithShortcuts, onClick, id]);

  return (
    <DropdownMenu.Root open={open}>
      <DropdownMenu.Trigger asChild>
        <div style={{ position: 'fixed', ...position }} />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          className={styles.ContextMenuContent}
          align="start"
          sideOffset={4}
          collisionPadding={10}
          onPointerDownOutside={onCloseContextMenu}
          onEscapeKeyDown={onCloseContextMenu}
        >
          {menusWithShortcuts.map((menu, index) =>
            'isSeparator' in menu ? (
              <DropdownMenu.Separator key={index} className={styles.ContextMenuSeparator} />
            ) : (
              <ContextMenuItem key={menu.key} menu={menu} onClick={(key) => onClick?.(key, context)} />
            ),
          )}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
});

export default ContextMenu;

const ContextMenuItem = memo(({ menu, onClick }: { menu: IMenuItem; onClick: (key: SQLOptType) => void }) => {
  const { styles } = useStyles();
  const { key, label, shortcutAction } = menu;

  return (
    <DropdownMenu.Item className={styles.ContextMenuItem} onClick={() => onClick(key)}>
      <ShortcutMenuLabel label={i18n(label)} shortcutAction={shortcutAction} />
    </DropdownMenu.Item>
  );
});
