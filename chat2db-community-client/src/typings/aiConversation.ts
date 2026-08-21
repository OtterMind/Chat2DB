export interface IConversationItem {
  id: number;
  title: string;
  isActive?: boolean;
  // Automatic and manual synchronization of table structure
  syncTableStructure?: boolean;
}
