package ai.chat2db.community.web.api.adapter.identity;

import ai.chat2db.community.domain.api.enums.RoleCodeEnum;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.web.api.model.http.CookieUtil;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import org.springframework.stereotype.Component;

@Component
public class IdentityAdapter implements IIdentityService {

    @Override
    public Long currentUserId() {
        Context context = ContextUtils.queryContext();
        if (context != null && context.getLoginUser() != null && context.getLoginUser().getId() != null) {
            return context.getLoginUser().getId();
        }
        try {
            Long userId = CookieUtil.getUserIdCookie();
            return userId == null ? RoleCodeEnum.DESKTOP.getDefaultUserId() : userId;
        } catch (Exception e) {
            return RoleCodeEnum.DESKTOP.getDefaultUserId();
        }
    }
}
