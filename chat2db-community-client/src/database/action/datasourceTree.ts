import { db } from '../db';
import { DataSourceTreeSchema, FilterInputSchema } from '../validation';

// Database operation class
class DataSourceTreeService {
  // create
  async initFilter(datasourceId: number) {
    const input = FilterInputSchema.parse({ datasourceId });
    const data = DataSourceTreeSchema.parse({
      datasourceId: input.datasourceId,
      hiddenTreeNodeIds: [],
    });
    
    await db.dataSourceTree.add(data);
  }

  // Query
  async getTreeHiddenTreeNodeIds() {
    const existData = await db.dataSourceTree.toArray();
    const data = {};
    for (const item of existData) {
      data[item.datasourceId] = item.hiddenTreeNodeIds;
    }
    return data;
  }

  // Modify the hiddenTreeNodeIds of a datasourceId
  async updateHiddenTreeNodeIds(datasourceId: number, hiddenTreeNodeIds: string[]) {
    const data = DataSourceTreeSchema.parse({ datasourceId, hiddenTreeNodeIds });
    await db.dataSourceTree.put(data);
  }

  // add or delete
  async addOrDeleteHiddenTreeNodeIds(datasourceId: number, hiddenTreeNodeId: string) {
    // If there is no data source, initialize it first
    let existData = await this.getTreeHiddenTreeNodeIds();
    if (!existData[datasourceId]) {
      await this.initFilter(datasourceId);
      existData = await this.getTreeHiddenTreeNodeIds();
    }

    // Remove if present, otherwise add
    if (existData[datasourceId].includes(hiddenTreeNodeId)) {
      existData[datasourceId].splice(existData[datasourceId].indexOf(hiddenTreeNodeId), 1);
    } else {
      existData[datasourceId].push(hiddenTreeNodeId);
    }

    await db.dataSourceTree
      .where('datasourceId')
      .equals(datasourceId)
      .modify({ hiddenTreeNodeIds: existData[datasourceId] });
  }

  // Delete
  async delete(datasourceId: number) {
    const input = FilterInputSchema.parse({ datasourceId });
    
    await db.dataSourceTree
      .where('datasourceId')
      .equals(input.datasourceId)
      .delete();
  }

  // Clean invalid data
  async cleanUpJunkData(deletedDataSourceId: number) {
    await this.delete(deletedDataSourceId);
  }
}

// Export service instance
export const dataSourceTreeService = new DataSourceTreeService();
