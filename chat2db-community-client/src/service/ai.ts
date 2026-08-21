import createRequest from './base';

// Get MCP configuration
const getMcpConfig = createRequest<void, string>('/api/mcp/config/copy');

export default {
  getMcpConfig,
};
