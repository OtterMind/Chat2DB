import React, { memo, forwardRef, useImperativeHandle, useMemo, useState, useRef } from 'react';
// import i18n from '@/i18n';
import { Dropdown } from 'antd';
import { IconfontSvg } from '@chat2db/ui';
import { Check } from 'lucide-react';

interface IProps {
  className?: string;
}

export interface ContextMenuRef {
  openDropdown: ({
    zIndex,
    position,
    event,
    dropdownsList,
    dropdownRender,
  }: {
    zIndex?: number;
    position?: 'fixed' | 'absolute';
    event: any;
    dropdownsList?: any[];
    dropdownRender?: React.ReactNode;
  }) => void;
  closeDropdown: () => void;
}

const ContextMenu = (props: IProps, ref) => {
  const { className } = props;
  // Parameters required to open the drop-down menu.
  const [dropdownParams, setDropdownParams] = useState<{
    zIndex?: number;
    position: 'fixed' | 'absolute';
    dropdownsList?: any[];
    dropdownRender?: React.ReactNode;
    clientX: number;
    clientY: number;
  } | null>(null);
  const dropdownRenderRef = useRef<HTMLDivElement>(null);
  const isHoveringRef = useRef(false);

  const openDropdown = ({ zIndex, position, event, dropdownsList, dropdownRender }) => {
    setDropdownParams(null);
    setTimeout(() => {
      setDropdownParams({
        zIndex,
        position,
        dropdownsList,
        dropdownRender,
        clientX: event.clientX,
        clientY: event.clientY,
      });
    }, 0);
  };

  const closeDropdown = () => {
    // Keep the menu open while the pointer is over dropdownRenderRef.
    if (isHoveringRef.current) {
      return;
    }
    setDropdownParams(null);
  };

  const renderChildren = (children: any) => {
    return children?.map((t) => {
      if (t.type === 'divider') {
        return { type: 'divider' as const };
      }
      return {
        key: t.key,
        onClick: () => {
          t.onClick?.();
        },
        icon:
          t.checked !== undefined ? (
            <Check opacity={t.checked ? 1 : 0} size={14} />
          ) : (
            t.icon && <IconfontSvg code={t.icon} size="lg" />
          ),
        label: t.label,
        children: renderChildren(t.children),
      };
    });
  };

  const menu = useMemo(() => {
    if (!dropdownParams) {
      return {
        items: [],
        style: { display: 'none' },
      };
    }

    const dropdownsItems = renderChildren(dropdownParams.dropdownsList);
    const selectedKeys = (dropdownParams.dropdownsList || [])
      .filter((item) => item.checked === true)
      .map((item) => item.key);

    return {
      items: dropdownsItems,
      selectable: true,
      selectedKeys,
      style: dropdownsItems?.length ? {} : { display: 'none' }, // Show only when menu items exist.
    };
  }, [dropdownParams]);

  useImperativeHandle(ref, () => ({
    openDropdown,
    closeDropdown,
  }));

  // Returning null prevents a newly opened dialog from rendering stale dropdown content
  // despite the reset and setTimeout.
  // Creating a fresh instance each time is also appropriate here.
  if (!dropdownParams) {
    return null;
  }

  return (
    <Dropdown
      className={className}
      menu={menu}
      trigger={['click']}
      open={!!dropdownParams}
      destroyPopupOnHide
      onOpenChange={(next) => {
        if (!next) {
          setDropdownParams(null);
        }
      }}
      dropdownRender={
        dropdownParams.dropdownRender
          ? () => {
              return (
                <div
                  style={{
                    overflow: 'hidden',
                  }}
                  ref={dropdownRenderRef}
                  onMouseEnter={() => {
                    isHoveringRef.current = true;
                  }}
                  onMouseLeave={() => {
                    isHoveringRef.current = false;
                    // closeDropdown();
                  }}
                >
                  {dropdownParams.dropdownRender}
                </div>
              );
            }
          : undefined
      }
      overlayStyle={{
        maxWidth: '50%',
      }}
    >
      <div
        style={{
          zIndex: dropdownParams.zIndex || 1,
          position: dropdownParams.position || 'fixed',
          left: dropdownParams?.clientX,
          top: dropdownParams?.clientY,
          height: 1,
          pointerEvents: 'none',
        }}
      />
    </Dropdown>
  );
};

export default memo(forwardRef<ContextMenuRef, IProps>(ContextMenu), () => {
  return true;
});
