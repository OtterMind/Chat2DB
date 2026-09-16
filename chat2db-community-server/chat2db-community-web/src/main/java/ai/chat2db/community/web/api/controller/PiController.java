package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.adapter.pi.PiOperationRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletionStage;
import org.springframework.web.bind.annotation.*;

@RestController
public class PiController {
    private final PiOperationRegistry operations;
    public PiController(PiOperationRegistry operations) { this.operations = operations; }

    @PostMapping(PiOperationRegistry.ENDPOINT)
    public CompletionStage<ObjectNode> invoke(@RequestBody JsonNode request) {
        return operations.invoke(request);
    }
}
