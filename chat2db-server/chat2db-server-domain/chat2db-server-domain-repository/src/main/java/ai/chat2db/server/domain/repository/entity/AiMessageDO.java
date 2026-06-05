package ai.chat2db.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 消息表
 */
@Getter
@Setter
@TableName("AI_MESSAGE")
public class AiMessageDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    private LocalDateTime gmtCreate;

    private String conversationId;
    private Long userId;
    private String messageId;
    private String role;
    private String content;
    private String thinking;
    private String promptType;
    private String sqlExtracted;
    private Integer sequenceNo;
}
