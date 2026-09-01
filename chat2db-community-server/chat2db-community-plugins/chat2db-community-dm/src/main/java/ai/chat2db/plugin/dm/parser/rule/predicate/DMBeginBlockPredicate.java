package ai.chat2db.plugin.dm.parser.rule.predicate;

import ai.chat2db.plugin.dm.parser.base.DMLexer;
import ai.chat2db.plugin.dm.parser.rule.DMRulePredicate;
import lombok.NoArgsConstructor;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

import java.util.List;
import java.util.Set;

@NoArgsConstructor
public class DMBeginBlockPredicate extends DMRulePredicate {


    public DMBeginBlockPredicate(int lookAheadTokens) {
        this.lookaheadTokens = lookAheadTokens;
    }

    private static final Set<Integer> MATCH_TOKENS = Set.of(DMLexer.BACKUP, DMLexer.RETURN);

    @Override
    public boolean matches(List<Token> tokens, int currentIndex) {
        return getNthValidTokenWithMatch(tokens, currentIndex, 1, Set.of("BACKUP", "RETURN")) == -1;

    }

    @Override
    public boolean matches(TokenStream tokenStream) {
        return getNthValidTokenWithMatch(tokenStream, 1, MATCH_TOKENS) == -1;
    }
}
