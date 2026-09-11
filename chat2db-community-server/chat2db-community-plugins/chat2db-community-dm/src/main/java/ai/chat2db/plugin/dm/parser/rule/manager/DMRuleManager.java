package ai.chat2db.plugin.dm.parser.rule.manager;

import ai.chat2db.plugin.dm.parser.rule.predicate.*;
import ai.chat2db.spi.parser.AbstractRuleManager;
import ai.chat2db.spi.IRulePredicate;

import java.util.HashMap;
import java.util.Map;

public class DMRuleManager extends AbstractRuleManager {


    private static final Map<String, IRulePredicate> RULE_PREDICATES = new HashMap<>();

    static {
        RULE_PREDICATES.put("PACKAGE", new DMCreatePackagePredicate(1));
        RULE_PREDICATES.put("IF", new DMIfBlockPredicate(20));
        RULE_PREDICATES.put("FUNCTION",new DMCreateFunctionPredicate(100));
        RULE_PREDICATES.put("PROCEDURE", new DMCreateProcedurePredicate(100));
        RULE_PREDICATES.put(("BEGIN"),new DMBeginBlockPredicate(1));
    }


    @Override
    public Map<String, IRulePredicate> getRules() {
        return RULE_PREDICATES;
    }


}
