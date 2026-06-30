package ai.chat2db.server.web.api.controller.ai.tongyi.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;


@Data
public class TongyiChatCompletions {

    /*
     * A unique identifier associated with this chat completions response.
     */
    private String id;

    /*
     * The collection of completions choices associated with this completions response.
     * Generally, `n` choices are generated per provided prompt with a default value of 1.
     * Token limits and other settings may limit the number of choices generated.
     */
    private List<TongyiChatChoice> choices;

    /*
     * Usage information for tokens processed and generated as part of this completions operation.
     */
    private TongyiChatCompletionsUsage usage;

    /**
     * Creates an instance of ChatCompletions class.
     *
     * @param id the id value to set.
     * @param choices the choices value to set.
     * @param usage the usage value to set.
     */
    @JsonCreator
    private TongyiChatCompletions(
        @JsonProperty(value = "id") String id,
        @JsonProperty(value = "choices") List<TongyiChatChoice> choices,
        @JsonProperty(value = "usage") TongyiChatCompletionsUsage usage) {
        this.id = id;
        this.choices = choices;
        this.usage = usage;
    }

}
