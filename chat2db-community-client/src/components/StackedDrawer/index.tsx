import { Drawer, Tooltip, type DrawerProps } from 'antd';
import { createStyles } from 'antd-style';
import { ArrowLeft } from 'lucide-react';
import {
  type CSSProperties,
  createContext,
  type Key,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from 'react';

interface StackedDrawerPage {
  content: ReactNode;
  contentStyle?: CSSProperties;
  extra?: ReactNode;
  key: Key;
  onClose?: () => void;
  title?: ReactNode;
}

interface StackedDrawerNavigation {
  registerPage: (page: StackedDrawerPage) => void;
  unregisterPage: (key: Key) => void;
}

const StackedDrawerContext = createContext<StackedDrawerNavigation | null>(null);

export interface StackedDrawerProps extends Omit<DrawerProps, 'afterOpenChange' | 'children' | 'onClose' | 'open'> {
  afterOpenChange?: (open: boolean) => void;
  children?: ReactNode;
  contentStyle?: CSSProperties;
  onClose?: () => void;
  open: boolean;
}

const useStyles = createStyles(({ css }) => ({
  page: css`
    height: 100%;
    padding: 24px;
    overflow-y: auto;
  `,
}));

const NestedDrawerPage = ({
  afterOpenChange,
  children,
  contentStyle,
  extra,
  navigation,
  onClose,
  open,
  title,
}: Pick<
  StackedDrawerProps,
  'afterOpenChange' | 'children' | 'contentStyle' | 'extra' | 'onClose' | 'open' | 'title'
> & {
  navigation: StackedDrawerNavigation;
}) => {
  const pageKey = useId();
  const wasOpen = useRef(false);
  const page = useMemo<StackedDrawerPage>(
    () => ({ content: children, contentStyle, extra, key: pageKey, onClose, title }),
    [children, contentStyle, extra, onClose, pageKey, title],
  );

  useEffect(() => {
    if (open) {
      navigation.registerPage(page);
    } else {
      navigation.unregisterPage(pageKey);
    }

    if (wasOpen.current !== open) {
      afterOpenChange?.(open);
      wasOpen.current = open;
    }
  }, [afterOpenChange, navigation, open, page, pageKey]);

  useEffect(
    () => () => {
      navigation.unregisterPage(pageKey);
    },
    [navigation, pageKey],
  );

  return null;
};

const RootDrawer = ({
  afterOpenChange,
  children,
  closeIcon,
  contentStyle,
  destroyOnClose = true,
  extra,
  onClose,
  open,
  styles: drawerStyles,
  title,
  ...drawerProps
}: StackedDrawerProps) => {
  const { styles } = useStyles();
  const [pages, setPages] = useState<StackedDrawerPage[]>([]);
  const pagesRef = useRef(pages);

  const updatePages = useCallback((updater: (current: StackedDrawerPage[]) => StackedDrawerPage[]) => {
    setPages((current) => {
      const next = updater(current);
      pagesRef.current = next;
      return next;
    });
  }, []);

  const registerPage = useCallback(
    (page: StackedDrawerPage) => {
      updatePages((current) => {
        const pageIndex = current.findIndex((item) => item.key === page.key);
        if (pageIndex < 0) return [...current, page];
        const next = [...current];
        next[pageIndex] = page;
        return next;
      });
    },
    [updatePages],
  );

  const unregisterPage = useCallback(
    (key: Key) => {
      updatePages((current) => {
        const pageIndex = current.findIndex((item) => item.key === key);
        return pageIndex < 0 ? current : current.slice(0, pageIndex);
      });
    },
    [updatePages],
  );

  const navigation = useMemo<StackedDrawerNavigation>(
    () => ({ registerPage, unregisterPage }),
    [registerPage, unregisterPage],
  );
  const activePage = pages[pages.length - 1];
  const hasPreviousPage = pages.length > 0;
  const visiblePages: StackedDrawerPage[] = [
    { content: children, contentStyle, extra, key: 'stacked-drawer-root', title },
    ...pages,
  ];

  const back = () => {
    const active = pagesRef.current[pagesRef.current.length - 1];
    if (!active) return;
    updatePages((current) => current.slice(0, -1));
    active.onClose?.();
  };

  return (
    <StackedDrawerContext.Provider value={navigation}>
      <Drawer
        {...drawerProps}
        afterOpenChange={(nextOpen) => {
          if (!nextOpen) updatePages(() => []);
          afterOpenChange?.(nextOpen);
        }}
        closeIcon={
          hasPreviousPage ? (
            <Tooltip title="返回上一级">
              <ArrowLeft aria-label="返回上一级" role="img" size={18} />
            </Tooltip>
          ) : (
            closeIcon
          )
        }
        destroyOnClose={destroyOnClose}
        extra={hasPreviousPage ? activePage?.extra : extra}
        onClose={hasPreviousPage ? back : onClose}
        open={open}
        styles={{
          ...drawerStyles,
          body: { ...drawerStyles?.body, overflow: 'hidden', padding: 0 },
        }}
        title={hasPreviousPage ? activePage?.title : title}
      >
        {visiblePages.map((page, index) => {
          const active = index === visiblePages.length - 1;
          return (
            <div
              aria-hidden={!active}
              className={styles.page}
              hidden={!active}
              key={page.key}
              style={page.contentStyle}
            >
              {page.content}
            </div>
          );
        })}
      </Drawer>
    </StackedDrawerContext.Provider>
  );
};

const StackedDrawer = (props: StackedDrawerProps) => {
  const parentNavigation = useContext(StackedDrawerContext);

  return parentNavigation ? <NestedDrawerPage {...props} navigation={parentNavigation} /> : <RootDrawer {...props} />;
};

export default StackedDrawer;
