package ai.chat2db.server.web.api.controller.ai.rest.model;

import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatChoice;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class RestAIChatCompletions {

    /*
     * A unique identifier associated with this chat completions response.
     */
    private String id;

    /*
     * The first timestamp associated with generation activity for this completions response,
     * represented as seconds since the beginning of the Unix epoch of 00:00 on 1 Jan 1970.
     */
    private int created;

    /**
     * model
     */
    private String model;

    /**
     * object
     */
    private String object;

    /*
     * The collection of completions choices associated with this completions response.
     * Generally, `n` choices are generated per provided prompt with a default value of 1.
     * Token limits and other settings may limit the number of choices generated.
     */
    @JsonProperty(value = "choices")
    private List<FastChatChoice> choices;


    /**
     * Creates an instance of ChatCompletions class.
     *
     * @param id the id value to set.
     * @param created the created value to set.
     * @param choices the choices value to set.
     */
    @JsonCreator
    private RestAIChatCompletions(
        @JsonProperty(value = "id") String id,
        @JsonProperty(value = "created") int created,
        @JsonProperty(value = "model") String model,
        @JsonProperty(value = "object") String object,
        @JsonProperty(value = "choices") List<FastChatChoice> choices) {
        this.id = id;
        this.created = created;
        this.model = model;
        this.object = object;
        this.choices = choices;
    }

    /**
     * Get the id property: A unique identifier associated with this chat completions response.
     *
     * @return the id value.
     */
    public String getId() {
        return this.id;
    }

    /**
     * Get the created property: The first timestamp associated with generation activity for this completions response,
     * represented as seconds since the beginning of the Unix epoch of 00:00 on 1 Jan 1970.
     *
     * @return the created value.
     */
    public int getCreated() {
        return this.created;
    }

    /**
     * Get the choices property: The collection of completions choices associated with this completions response.
     * Generally, `n` choices are generated per provided prompt with a default value of 1. Token limits and other
     * settings may limit the number of choices generated.
     *
     * @return the choices value.
     */
    public List<FastChatChoice> getChoices() {
        return this.choices;
    }



}
