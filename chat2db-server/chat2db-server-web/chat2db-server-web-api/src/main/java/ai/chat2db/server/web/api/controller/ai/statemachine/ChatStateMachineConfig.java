package ai.chat2db.server.web.api.controller.ai.statemachine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.StateMachine;

import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import ai.chat2db.server.web.api.controller.ai.statemachine.actions.SelectTablesAction;
import ai.chat2db.server.web.api.controller.ai.statemachine.actions.BuildPromptAction;
import ai.chat2db.server.web.api.controller.ai.statemachine.actions.FetchSchemaAction;
import ai.chat2db.server.web.api.controller.ai.statemachine.actions.SaveAiCommentAction;
import ai.chat2db.server.web.api.controller.ai.statemachine.actions.StreamAction;
import ai.chat2db.server.web.api.controller.ai.statemachine.actions.ExplainAction;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableStateMachineFactory
public class ChatStateMachineConfig extends StateMachineConfigurerAdapter<ChatState, ChatEvent> {

    @Autowired
    private SelectTablesAction selectTablesAction;

    @Autowired
    private FetchSchemaAction fetchSchemaAction;

    @Autowired
    private BuildPromptAction buildPromptAction;

    @Autowired
    private StreamAction streamAction;

    @Autowired
    private ExplainAction explainAction;

    @Autowired
    private SaveAiCommentAction saveAiCommentAction;

    @Override
    public void configure(StateMachineStateConfigurer<ChatState, ChatEvent> states) throws Exception {
        states
            .withStates()
            .initial(ChatState.IDLE)
            .state(ChatState.AUTO_SELECTING_TABLES, selectTablesAction)
            .state(ChatState.FETCHING_TABLE_SCHEMA, fetchSchemaAction)
            .state(ChatState.EXECUTING_EXPLAIN, explainAction)
            .state(ChatState.BUILDING_PROMPT, buildPromptAction)
            .state(ChatState.STREAMING, streamAction)
            .end(ChatState.COMPLETED)
            .end(ChatState.FAILED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<ChatState, ChatEvent> transitions) throws Exception {
        transitions
            .withExternal()
                .source(ChatState.IDLE).target(ChatState.FETCHING_TABLE_SCHEMA)
                .event(ChatEvent.TABLES_PROVIDED)
            .and()
            .withExternal()
                .source(ChatState.IDLE).target(ChatState.AUTO_SELECTING_TABLES)
                .event(ChatEvent.TABLES_NOT_PROVIDED)
            .and()
            .withExternal()
                .source(ChatState.IDLE).target(ChatState.BUILDING_PROMPT)
                .event(ChatEvent.TABLES_NOT_NEEDED)
            .and()
            .withExternal()
                .source(ChatState.IDLE).target(ChatState.EXECUTING_EXPLAIN)
                .event(ChatEvent.EXPLAIN_TABLES_NOT_SELECTED)
            .and()
            .withExternal()
                .source(ChatState.AUTO_SELECTING_TABLES).target(ChatState.FETCHING_TABLE_SCHEMA)
                .event(ChatEvent.AUTO_SELECT_DONE)
            .and()
            .withExternal()
                .source(ChatState.AUTO_SELECTING_TABLES).target(ChatState.FAILED)
                .event(ChatEvent.AUTO_SELECT_FAILED)
            .and()
            .withExternal()
                .source(ChatState.FETCHING_TABLE_SCHEMA).target(ChatState.BUILDING_PROMPT)
                .event(ChatEvent.SCHEMA_FETCHED)
            .and()
            .withExternal()
                .source(ChatState.FETCHING_TABLE_SCHEMA).target(ChatState.FAILED)
                .event(ChatEvent.FETCH_SCHEMA_FAILED)
            .and()
            .withExternal()
                .source(ChatState.EXECUTING_EXPLAIN).target(ChatState.BUILDING_PROMPT)
                .event(ChatEvent.EXPLAIN_EXECUTED)
            .and()
            .withExternal()
                .source(ChatState.EXECUTING_EXPLAIN).target(ChatState.FETCHING_TABLE_SCHEMA)
                .event(ChatEvent.EXPLAIN_TABLES_SELECTED)
            .and()
            .withExternal()
                .source(ChatState.EXECUTING_EXPLAIN).target(ChatState.AUTO_SELECTING_TABLES)
                .event(ChatEvent.EXPLAIN_TABLES_NOT_SELECTED)
            .and()
            .withExternal()
                .source(ChatState.EXECUTING_EXPLAIN).target(ChatState.BUILDING_PROMPT)
                .event(ChatEvent.EXPLAIN_FAILED)
            .and()
            .withExternal()
                .source(ChatState.EXECUTING_EXPLAIN).target(ChatState.BUILDING_PROMPT)
                .event(ChatEvent.EXPLAIN_NOT_NEEDED)
            .and()
            .withExternal()
                .source(ChatState.BUILDING_PROMPT).target(ChatState.STREAMING)
                .event(ChatEvent.PROMPT_BUILT)
            .and()
            .withExternal()
                .source(ChatState.BUILDING_PROMPT).target(ChatState.FAILED)
                .event(ChatEvent.PROMPT_BUILD_FAILED)
            .and()
            .withExternal()
                .source(ChatState.STREAMING).target(ChatState.COMPLETED)
                .action(saveAiCommentAction)
                .event(ChatEvent.STREAM_FINISHED)
            .and()
            .withExternal()
                .source(ChatState.STREAMING).target(ChatState.FAILED)
                .event(ChatEvent.AI_CALL_FAILED)
            .and()
            .withExternal()
                .source(ChatState.STREAMING).target(ChatState.FAILED)
                .event(ChatEvent.CANCEL);
    }

    @Override
    public void configure(org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer<ChatState, ChatEvent> config) throws Exception {
        config.withConfiguration()
            .listener(new StateMachineListenerAdapter<ChatState, ChatEvent>() {
                @Override
                public void stateChanged(org.springframework.statemachine.state.State<ChatState, ChatEvent> from, org.springframework.statemachine.state.State<ChatState, ChatEvent> to) {
                    log.info("[StateMachine] State changed: {} -> {}",
                        from != null ? from.getId() : "null",
                        to != null ? to.getId() : "null");
                }

                @Override
                public void stateMachineStarted(StateMachine<ChatState, ChatEvent> sm) {
                    log.info("[StateMachine] StateMachine started with id: {}, initial state: {}",
                        sm.getId(),
                        sm.getState() != null ? sm.getState().getId() : "null");
                }

                @Override
                public void stateMachineStopped(StateMachine<ChatState, ChatEvent> sm) {
                    log.info("[StateMachine] StateMachine stopped with id: {}, final state: {}",
                        sm.getId(),
                        sm.getState() != null ? sm.getState().getId() : "null");
                }

                @Override
                public void stateEntered(org.springframework.statemachine.state.State<ChatState, ChatEvent> state) {
                    log.info("[StateMachine] State entered: {}", state.getId());
                }

                @Override
                public void stateExited(org.springframework.statemachine.state.State<ChatState, ChatEvent> state) {
                    log.info("[StateMachine] State exited: {}", state.getId());
                }
            });
    }
}
