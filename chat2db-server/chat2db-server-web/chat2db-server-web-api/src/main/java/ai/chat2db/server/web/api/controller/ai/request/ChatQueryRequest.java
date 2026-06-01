package ai.chat2db.server.web.api.controller.ai.request;

import java.util.List;

import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import ai.chat2db.server.web.api.controller.data.source.request.DataSourceBaseRequest;

import lombok.Data;

/**
 * chat查询入参
 *
 * @author moji
 * @version ChatQueryRequest.java, v 0.1 2023年04月02日 13:28 moji Exp $
 * @date 2023/04/02
 */
@Data
public class ChatQueryRequest extends DataSourceBaseRequest {

    /**
     * 输入消息
     */
    private String message;

    /**
     * SQL功能类型
     * @see PromptType
     */
    private String promptType;

    /**
     * 表名列表
     */
    private List<String> tableNames;

    /**
     * 目标SQL数据类型
     * @see ai.chat2db.server.domain.support.enums.DbTypeEnum
     */
    private String destSqlType;

    /**
     * 更多备注信息：如要求或限制条件等
     */
    private String ext;

    /**
     * 前端会话 ID，用于同一 AI 面板内的连续对话
     */
    private String conversationId;

    /**
     * 最近几轮对话历史，JSON 字符串
     */
    private String history;

    /**
     * 上一次 AI 输出的 SQL
     */
    private String previousSql;

    /**
     * 是否为基于上一次 SQL 的修正请求
     */
    private Boolean isRevision;

    /**
     * 大请求体临时缓存 ID，用于避免 SSE GET URL 过长
     */
    private String payloadId;
}
