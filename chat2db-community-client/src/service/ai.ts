import createRequest from './base';
import { IAIModel } from '@/typings/ai';

// /api/v2/ai/model/list
/**
 * Get model list
 */
const getModelList = createRequest<void, IAIModel[]>('/api/v2/ai/model/list');

// Get MCP configuration
const getMcpConfig = createRequest<void, string>('/api/mcp/config/copy');

export default {
  getModelList,
  getMcpConfig,
};
