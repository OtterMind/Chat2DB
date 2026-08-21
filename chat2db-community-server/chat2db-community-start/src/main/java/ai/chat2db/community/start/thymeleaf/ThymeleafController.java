package ai.chat2db.community.start.thymeleaf;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page application entry points, including legacy browser routes.
 */
@Controller
@Order(Integer.MIN_VALUE)
public class ThymeleafController {

    /**
     * Returns the main single-page application entry for mapped browser routes.
     * <p>
     * Endpoint: {@code GET multiple mapped routes}.
     *
     * @return string value for the request.
     */
    @GetMapping({
            "/",
            "/connections",
            "/dashboard",
            "/team",
            "/workspace",
            "/permission",
            "/chat",
            "/chat.html",
            "/approval",
            "/organization",
            "/reset_password",
            "/web/",
            "/web/**",
            "/login/**",
            "/ai/**",
            "/model/**",
            "/pay",
            "/invite",
            "/price",
            "/settings/**",
            "/chat/share/**",
            "/dashboard/share/**"
    })
    public String index() {
        return "index";
    }
}
