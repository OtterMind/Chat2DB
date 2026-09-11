import { useEffect, useMemo, useRef, useState } from 'react';
import PageTitle from '@/components/PageTitle';
import pluginService from '@/service/plugin';
import { IPluginItem } from '@/typings/plugin';
import { openWebPage } from '@/utils/url';
import { useStyles } from './style';
import PluginMenuItem from '../PluginMenuItem';
import i18n from '@/i18n';
import { useUserStore } from '@/store/session';
import { staticMessage } from '@chat2db/ui';
interface PluginMenuListProps {
  curPlugin?: IPluginItem;
  onClick: (plugin: IPluginItem) => void;
}
const PluginMenuList = ({ curPlugin, onClick }: PluginMenuListProps) => {
  const [pluginList, setPluginList] = useState<IPluginItem[]>([]);
  const { styles } = useStyles();
  const mountedRef = useRef(true);

  const { curUser } = useUserStore((s) => ({
    curUser: s.curUser,
  }));

  const isVip = useMemo(() => curUser?.vip || curUser?.activated, [curUser?.vip, curUser?.activated]);

  useEffect(() => {
    mountedRef.current = true;
    queryPluginList();
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const queryPluginList = async () => {
    const res = await pluginService.queryPluginList();
    if (!mountedRef.current) return;
    setPluginList(res);

    if (!curPlugin && res[0]) {
      if (!isVip) {
        return staticMessage.error(i18n('plugin.item.usage.status.needBuy'));
      } else {
        res[0].token = await pluginService.queryToken();
      }
      handleClickItem(res[0]);
    }
  };

  const handleClickItem = async (plugin) => {
    if (!plugin.token) {
      if (!isVip) {
        return staticMessage.error(i18n('plugin.item.usage.status.needBuy'));
      }
      plugin.token = await pluginService.queryToken();
    }
    onClick(plugin);
  };

  const handleButtonClick = (plugin) => {
    pluginService.addPluginDownloadCount({ id: plugin.id });
    openWebPage(plugin.downloadUrl || plugin.url);
  };
  return (
    <div className={styles.container}>
      <PageTitle title={i18n('plugin.title')} style={{ padding: '16px' }} />

      <div className={styles.listWrapper}>
        {pluginList.map((plugin) => (
          <PluginMenuItem
            isActive={plugin.name === curPlugin?.name}
            key={plugin.id}
            plugin={plugin}
            onClick={() => onClick(plugin)}
            onButtonClick={() => handleButtonClick(plugin)}
          />
        ))}
      </div>
    </div>
  );
};

export default PluginMenuList;
