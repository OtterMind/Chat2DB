package ai.chat2db.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话表
 */
@Getter
@Setter
@TableName("AI_CONVERSATION")
public class AiConversationDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;

    private String conversationId;
    private Long userId;
    private String title;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
    private Integer messageCount;
    private String lastMessagePreview;
    private String status;
}
