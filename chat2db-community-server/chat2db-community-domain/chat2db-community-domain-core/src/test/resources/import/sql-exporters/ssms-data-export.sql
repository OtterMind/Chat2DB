SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
SET ANSI_PADDING ON
GO
CREATE TABLE [dbo].[demo_item] ([id] int NOT NULL, [name] nvarchar(64) NULL)
GO
INSERT INTO [dbo].[demo_item] ([id], [name]) VALUES (1, N'SSMS; one')
GO
UPDATE [dbo].[demo_item] SET [name] = N'two' WHERE [id] = 1
GO
