package ai.chat2db.plugin.dm.parser.rule.predicate;

import ai.chat2db.plugin.dm.parser.rule.DMRulePredicate;
import ai.chat2db.spi.util.TokenUtil;
import org.antlr.v4.runtime.Token;

import java.util.List;

public class DMCreatePackagePredicate extends DMRulePredicate {


    public DMCreatePackagePredicate(int lookAheadTokens) {
       this.lookaheadTokens = lookAheadTokens;
    }


    @Override
    public boolean needUpgradeBlock(List<Token> tokens, int currentIndex) {
        int lookaheadIndex = currentIndex + 1;
        int valuableTokenFound = 0;
        while (lookaheadIndex < tokens.size() && valuableTokenFound < lookaheadTokens) {
            Token token = tokens.get(lookaheadIndex);
            if (!TokenUtil.hasValuableText(token)) {
                lookaheadIndex++;
                continue;
            }
            valuableTokenFound++;
            String text = token.getText().trim();
            if (valuableTokenFound == 1 && "BODY".equalsIgnoreCase(text)) {
                return false;
            }
            lookaheadIndex++;

        }

        return true;
    }
}
