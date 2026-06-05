package ai.chat2db.server.domain.core.impl;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import ai.chat2db.server.domain.api.enums.RoleCodeEnum;
import ai.chat2db.server.domain.api.model.AiConversation;
import ai.chat2db.server.domain.api.model.AiConversationDetail;
import ai.chat2db.server.domain.api.model.AiMessage;
import ai.chat2db.server.domain.api.model.DataSource;
import ai.chat2db.server.domain.api.param.ai.AiConversationCreateParam;
import ai.chat2db.server.domain.api.param.ai.AiConversationQueryParam;
import ai.chat2db.server.domain.api.param.ai.AiMessageSaveParam;
import ai.chat2db.server.domain.api.service.AiConversationService;
import ai.chat2db.server.domain.api.service.DataSourceService;
import ai.chat2db.server.domain.core.converter.AiConversationConverter;
import ai.chat2db.server.domain.core.util.PermissionUtils;
import ai.chat2db.server.domain.repository.Dbutils;
import ai.chat2db.server.domain.repository.entity.AiConversationDO;
import ai.chat2db.server.domain.repository.entity.AiMessageDO;
import ai.chat2db.server.domain.repository.mapper.AiConversationMapper;
import ai.chat2db.server.domain.repository.mapper.AiMessageMapper;
import ai.chat2db.server.tools.base.wrapper.ServicePage;
import ai.chat2db.server.tools.common.exception.DataNotFoundException;
import ai.chat2db.server.tools.common.util.ContextUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiConversationServiceImpl implements AiConversationService {

    private static final int MAX_ACTIVE_CONVERSATIONS = 100;

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String STATUS_DELETED = "DELETED";

    private AiConversationMapper getConversationMapper() {
        return Dbutils.getMapper(AiConversationMapper.class);
    }

    private AiMessageMapper getMessageMapper() {
        return Dbutils.getMapper(AiMessageMapper.class);
    }

    @Autowired
    private AiConversationConverter converter;

    @Autowired
    private DataSourceService dataSourceService;

    @Override
    public String create(AiConversationCreateParam param) {
        Long userId = param.getUserId() != null ? param.getUserId() : ContextUtils.getUserId();
        if (StringUtils.isNotBlank(param.getConversationId())) {
            AiConversation existing = findByConversationId(param.getConversationId());
            if (existing != null) {
                PermissionUtils.checkOperationPermission(existing.getUserId());
                return existing.getConversationId();
            }
        }
        enforceQuota(userId);

        AiConversationDO conversationDO = converter.createParam2do(param);
        String conversationId = StringUtils.isNotBlank(param.getConversationId())
            ? param.getConversationId()
            : UUID.randomUUID().toString();
        conversationDO.setConversationId(conversationId);
        conversationDO.setUserId(userId);
        conversationDO.setStatus(STATUS_ACTIVE);
        conversationDO.setMessageCount(0);
        conversationDO.setGmtCreate(LocalDateTime.now());
        conversationDO.setGmtModified(LocalDateTime.now());
        if (StringUtils.isBlank(conversationDO.getTitle())) {
            conversationDO.setTitle("新对话");
        }
        getConversationMapper().insert(conversationDO);
        return conversationId;
    }

    @Override
    public void updateTitle(String conversationId, String title) {
        if (StringUtils.isBlank(conversationId) || StringUtils.isBlank(title)) {
            return;
        }
        String trimmed = title.length() > 50 ? title.substring(0, 50) : title;
        getConversationMapper().update(null,
            new LambdaUpdateWrapper<AiConversationDO>()
                .eq(AiConversationDO::getConversationId, conversationId)
                .set(AiConversationDO::getTitle, trimmed)
                .set(AiConversationDO::getGmtModified, LocalDateTime.now()));
    }

    @Override
    public void deleteWithPermission(String conversationId, Long userId) {
        AiConversation conv = findByConversationId(conversationId);
        if (conv == null) {
            return;
        }
        PermissionUtils.checkOperationPermission(conv.getUserId());
        getConversationMapper().update(null,
            new LambdaUpdateWrapper<AiConversationDO>()
                .eq(AiConversationDO::getConversationId, conversationId)
                .set(AiConversationDO::getStatus, STATUS_DELETED)
                .set(AiConversationDO::getGmtModified, LocalDateTime.now()));
    }

    @Override
    public ServicePage<AiConversation> queryPage(AiConversationQueryParam param) {
        if (param.getPageNo() == null) {
            param.setPageNo(1);
        }
        if (param.getPageSize() == null) {
            param.setPageSize(20);
        }
        if (StringUtils.isBlank(param.getStatus())) {
            param.setStatus(STATUS_ACTIVE);
        }
        LambdaQueryWrapper<AiConversationDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiConversationDO::getUserId, param.getUserId())
            .eq(AiConversationDO::getStatus, param.getStatus())
            .like(StringUtils.isNotBlank(param.getSearchKey()), AiConversationDO::getTitle, param.getSearchKey())
            .eq(param.getDataSourceId() != null, AiConversationDO::getDataSourceId, param.getDataSourceId())
            .orderByDesc(AiConversationDO::getGmtModified);

        IPage<AiConversationDO> page = getConversationMapper().selectPage(new Page<>(param.getPageNo(), param.getPageSize()), wrapper);
        List<AiConversation> records = converter.do2dto(page.getRecords());
        if (CollectionUtils.isNotEmpty(records)) {
            List<Long> dataSourceIds = records.stream()
                .map(AiConversation::getDataSourceId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(dataSourceIds)) {
                List<DataSource> dataSources = dataSourceService.listQuery(dataSourceIds, null);
                records.forEach(c -> dataSources.stream()
                    .filter(ds -> ds.getId().equals(c.getDataSourceId()))
                    .findFirst()
                    .ifPresent(ds -> c.setDataSourceName(ds.getAlias())));
            }
        }
        return ServicePage.of(records, page.getTotal(), param.getPageNo(), param.getPageSize());
    }

    @Override
    public AiConversationDetail getDetail(String conversationId, Long userId) {
        AiConversation conversation = findByConversationId(conversationId);
        if (conversation == null || STATUS_DELETED.equals(conversation.getStatus())) {
            return null;
        }
        PermissionUtils.checkBaseQueryPermission(conversation.getUserId());

        List<AiMessageDO> messageDOS = getMessageMapper().selectList(
            new LambdaQueryWrapper<AiMessageDO>()
                .eq(AiMessageDO::getConversationId, conversationId)
                .orderByAsc(AiMessageDO::getSequenceNo)
        );
        List<AiMessage> messages = converter.do2MessageDto(messageDOS);

        if (conversation.getDataSourceId() != null) {
            DataSource dataSource = dataSourceService.queryExistent(conversation.getDataSourceId(), null);
            if (dataSource != null) {
                conversation.setDataSourceName(dataSource.getAlias());
            }
        }

        AiConversationDetail detail = new AiConversationDetail();
        detail.setConversation(conversation);
        detail.setMessages(messages);
        return detail;
    }

    @Override
    public AiConversation findByConversationId(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return null;
        }
        AiConversationDO conversationDO = getConversationMapper().selectOne(
            new LambdaQueryWrapper<AiConversationDO>()
                .eq(AiConversationDO::getConversationId, conversationId)
                .last("LIMIT 1")
        );
        if (conversationDO == null) {
            return null;
        }
        return converter.do2dto(conversationDO);
    }

    @Override
    public void appendMessage(AiMessageSaveParam param) {
        if (param == null || StringUtils.isBlank(param.getConversationId())) {
            return;
        }
        AiMessageDO messageDO = converter.saveParam2do(param);
        if (messageDO.getGmtCreate() == null) {
            messageDO.setGmtCreate(LocalDateTime.now());
        }
        getMessageMapper().insert(messageDO);
    }

    @Override
    public void appendMessageTurn(String conversationId,
                                   Long userId,
                                   String userMessageId,
                                   String userContent,
                                   String assistantMessageId,
                                   String assistantContent,
                                   String assistantThinking,
                                   String promptType,
                                   String sqlExtracted) {
        if (StringUtils.isBlank(conversationId) || StringUtils.isBlank(userContent)
            || StringUtils.isBlank(assistantContent)) {
            return;
        }

        AiConversationDO conv = getConversationMapper().selectOne(
            new LambdaQueryWrapper<AiConversationDO>()
                .eq(AiConversationDO::getConversationId, conversationId)
                .last("LIMIT 1")
        );
        if (conv == null) {
            return;
        }
        if (STATUS_DELETED.equals(conv.getStatus())) {
            return;
        }

        int nextSeq = (conv.getMessageCount() == null ? 0 : conv.getMessageCount());

        AiMessageDO userMsg = new AiMessageDO();
        userMsg.setConversationId(conversationId);
        userMsg.setUserId(userId);
        userMsg.setMessageId(userMessageId);
        userMsg.setRole("user");
        userMsg.setContent(userContent);
        userMsg.setPromptType(promptType);
        userMsg.setSequenceNo(nextSeq);
        userMsg.setGmtCreate(LocalDateTime.now());
        getMessageMapper().insert(userMsg);

        AiMessageDO assistantMsg = new AiMessageDO();
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setUserId(userId);
        assistantMsg.setMessageId(assistantMessageId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(assistantContent);
        assistantMsg.setThinking(assistantThinking);
        assistantMsg.setPromptType(promptType);
        assistantMsg.setSqlExtracted(sqlExtracted);
        assistantMsg.setSequenceNo(nextSeq + 1);
        assistantMsg.setGmtCreate(LocalDateTime.now());
        getMessageMapper().insert(assistantMsg);

        String preview = assistantContent.length() > 200
            ? assistantContent.substring(0, 200) + "..."
            : assistantContent;
        preview = preview.replaceAll("\\s+", " ").trim();

        getConversationMapper().update(null,
            new LambdaUpdateWrapper<AiConversationDO>()
                .eq(AiConversationDO::getConversationId, conversationId)
                .set(AiConversationDO::getMessageCount, nextSeq + 2)
                .set(AiConversationDO::getLastMessagePreview, preview)
                .set(AiConversationDO::getGmtModified, LocalDateTime.now()));
    }

    @Override
    public List<AiMessage> listRecentMessages(String conversationId, int limit) {
        if (StringUtils.isBlank(conversationId) || limit <= 0) {
            return Collections.emptyList();
        }
        List<AiMessageDO> recent = getMessageMapper().selectList(
            new LambdaQueryWrapper<AiMessageDO>()
                .eq(AiMessageDO::getConversationId, conversationId)
                .orderByDesc(AiMessageDO::getSequenceNo)
                .last("LIMIT " + limit)
        );
        Collections.reverse(recent);
        return converter.do2MessageDto(recent);
    }

    private void enforceQuota(Long userId) {
        if (userId == null) {
            return;
        }
        if (RoleCodeEnum.DESKTOP.getDefaultUserId().equals(userId)) {
            return;
        }
        Long activeCount = getConversationMapper().selectCount(
            new LambdaQueryWrapper<AiConversationDO>()
                .eq(AiConversationDO::getUserId, userId)
                .eq(AiConversationDO::getStatus, STATUS_ACTIVE)
        );
        if (activeCount == null || activeCount < MAX_ACTIVE_CONVERSATIONS) {
            return;
        }
        long toArchive = activeCount - MAX_ACTIVE_CONVERSATIONS + 1;
        if (toArchive <= 0) {
            return;
        }
        List<AiConversationDO> oldest = getConversationMapper().selectList(
            new LambdaQueryWrapper<AiConversationDO>()
                .eq(AiConversationDO::getUserId, userId)
                .eq(AiConversationDO::getStatus, STATUS_ACTIVE)
                .orderByAsc(AiConversationDO::getGmtModified)
                .last("LIMIT " + toArchive)
        );
        if (CollectionUtils.isEmpty(oldest)) {
            return;
        }
        List<Long> ids = oldest.stream().map(AiConversationDO::getId).collect(Collectors.toList());
        getConversationMapper().update(null,
            new LambdaUpdateWrapper<AiConversationDO>()
                .in(AiConversationDO::getId, ids)
                .set(AiConversationDO::getStatus, STATUS_ARCHIVED)
                .set(AiConversationDO::getGmtModified, LocalDateTime.now()));
    }
}
