import createRequest from './base';
import type { ServiceAppConfig } from '@/typings/settings';

const getAppConfig = createRequest<void, ServiceAppConfig>('/api/oauth/get_app_config', {
  method: 'get',
  errorLevel: false,
});

export default { getAppConfig };
