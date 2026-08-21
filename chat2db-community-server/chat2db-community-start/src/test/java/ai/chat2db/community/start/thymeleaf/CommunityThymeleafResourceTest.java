package ai.chat2db.community.start.thymeleaf;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class CommunityThymeleafResourceTest {

    @Test
    void communityOwnsOnlyItsSpaEntry() throws IOException {
        ClassPathResource index = new ClassPathResource("thymeleaf/index.html");

        assertTrue(index.exists());
        assertTrue(index.getContentAsString(StandardCharsets.UTF_8).contains("Chat2DB Community"));
        assertFalse(new ClassPathResource("thymeleaf/login-callback.html").exists());
        assertFalse(new ClassPathResource("thymeleaf/template.html").exists());
    }

    @Test
    void legacyChatRouteUsesSpaEntry() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThymeleafController()).build();

        mockMvc.perform(get("/chat.html"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    void enterpriseKnowledgeRouteIsNotOwnedByCommunity() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThymeleafController()).build();

        mockMvc.perform(get("/knowledge-management"))
                .andExpect(status().isNotFound());
    }
}
