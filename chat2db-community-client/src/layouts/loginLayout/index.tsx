import { useShortcutManager } from '@/utils/shortcutManager';
import { Outlet } from 'umi';
import useInitQuery from '../init/initQuery';

function LoginLayout() {
  useShortcutManager();
  const { initQueryLoaded } = useInitQuery();

  return initQueryLoaded ? <Outlet /> : null;
}

export default LoginLayout;
