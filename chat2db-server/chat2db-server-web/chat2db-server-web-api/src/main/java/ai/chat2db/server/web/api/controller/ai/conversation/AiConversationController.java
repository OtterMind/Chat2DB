package ai.chat2db.server.web.api.controller.ai.conversation;

import java.util.List;

import ai.chat2db.server.domain.api.model.AiConversation;
import ai.chat2db.server.domain.api.model.AiConversationDetail;
import ai.chat2db.server.domain.api.param.ai.AiConversationCreateParam;
import ai.chat2db.server.domain.api.param.ai.AiConversationQueryParam;
import ai.chat2db.server.domain.api.service.AiConversationService;
import ai.chat2db.server.tools.base.wrapper.ServicePage;
import ai.chat2db.server.tools.base.wrapper.result.ActionResult;
import ai.chat2db.server.tools.base.wrapper.result.DataResult;
import ai.chat2db.server.tools.base.wrapper.result.web.WebPageResult;
import ai.chat2db.server.tools.common.util.ContextUtils;
import ai.chat2db.server.web.api.controller.ai.conversation.converter.AiConversationWebConverter;
import ai.chat2db.server.web.api.controller.ai.conversation.request.AiConversationCreateRequest;
import ai.chat2db.server.web.api.controller.ai.conversation.request.AiConversationQueryRequest;
import ai.chat2db.server.web.api.controller.ai.conversation.request.AiConversationRenameRequest;
import ai.chat2db.server.web.api.controller.ai.conversation.vo.AiConversationDetailVO;
import ai.chat2db.server.web.api.controller.ai.conversation.vo.AiConversationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/api/ai/conversation")
@RestController
public class AiConversationController {

    @Autowired
    private AiConversationService aiConversationService;

    @Autowired
    private AiConversationWebConverter webConverter;

    @PostMapping("/create")
    public DataResult<String> create(@RequestBody AiConversationCreateRequest request) {
        AiConversationCreateParam param = webConverter.req2param(request);
        param.setUserId(ContextUtils.getUserId());
        return DataResult.of(aiConversationService.create(param));
    }

    @GetMapping("/list")
    public WebPageResult<AiConversationVO> list(AiConversationQueryRequest request) {
        AiConversationQueryParam param = webConverter.queryReq2param(request);
        param.setUserId(ContextUtils.getUserId());
        if (param.getPageNo() == null) {
            param.setPageNo(request.getPageNo());
        }
        if (param.getPageSize() == null) {
            param.setPageSize(request.getPageSize());
        }
        ServicePage<AiConversation> page = aiConversationService.queryPage(param);
        List<AiConversationVO> vos = webConverter.dto2vo(page.getData());
        return WebPageResult.of(vos, page.getTotal(), request.getPageNo(), request.getPageSize());
    }

    @GetMapping("/{conversationId}")
    public DataResult<AiConversationDetailVO> get(@PathVariable("conversationId") String conversationId) {
        AiConversationDetail detail = aiConversationService.getDetail(conversationId, ContextUtils.getUserId());
        if (detail == null) {
            return null;
        }
        return DataResult.of(webConverter.detail2voWithMessages(detail));
    }

    @PostMapping("/{conversationId}/rename")
    public ActionResult rename(@PathVariable("conversationId") String conversationId,
                                @RequestBody AiConversationRenameRequest request) {
        aiConversationService.updateTitle(conversationId, request.getTitle());
        return ActionResult.isSuccess();
    }

    @DeleteMapping("/{conversationId}")
    public ActionResult delete(@PathVariable("conversationId") String conversationId) {
        aiConversationService.deleteWithPermission(conversationId, ContextUtils.getUserId());
        return ActionResult.isSuccess();
    }
}
