package ai.chat2db.plugin.dm.parser.rule.predicate;

import ai.chat2db.plugin.dm.parser.base.DMLexer;
import ai.chat2db.plugin.dm.parser.rule.DMRulePredicate;
import ai.chat2db.spi.util.TokenUtil;
import lombok.NoArgsConstructor;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.apache.commons.lang3.StringUtils;

import java.util.List;


@NoArgsConstructor
public class DMCreateFunctionPredicate extends DMRulePredicate {


    public DMCreateFunctionPredicate(int lookAheadTokens) {
        this.lookaheadTokens = lookAheadTokens;
    }

    @Override
    public boolean matches(List<Token> tokens, int currentIndex) {
        if (lookaheadTokens == 0) {
            lookaheadTokens = tokens.size();
        }
        int lookaheadIndex = currentIndex + 1;
        int valuableTokenFound = 0;

        boolean encounteredIsOrAs = false;
        while (lookaheadIndex < tokens.size() && valuableTokenFound < lookaheadTokens) {
            Token token = tokens.get(lookaheadIndex);
            if (!TokenUtil.hasValuableText(token)) {
                lookaheadIndex++;
                continue;
            }
            lookaheadIndex++;
            valuableTokenFound++;
            String text = token.getText().trim();
            if (StringUtils.equalsAnyIgnoreCase(text, "IS", "AS")) {
                encounteredIsOrAs = true;
            } else if ("BEGIN".equalsIgnoreCase(text)) {
                return true;
            } else if (!encounteredIsOrAs && ";".equalsIgnoreCase(text)) {
                return false;
            } else if ("LANGUAGE".equalsIgnoreCase(text)) {
                return false;
            }

        }
        return false;

    }

    @Override
    public boolean matches(TokenStream tokenStream) {

        int valuableTokenFound = 0;
        int offset = 2;
        boolean encounteredIsOrAs = false;

        while (valuableTokenFound < lookaheadTokens) {
            Token token = tokenStream.LT(offset);
            int tokenType = token.getType();
            if (tokenType == Token.EOF) {
                return false;
            }

            if (!TokenUtil.hasValuableText(token)) {
                offset++;
                continue;
            }

            valuableTokenFound++;
            String text = token.getText().trim();
            if (DMLexer.AS == tokenType || DMLexer.IS == tokenType) {
                encounteredIsOrAs = true;
            } else if (DMLexer.BEGIN == tokenType) {
                return true;
            } else if (!encounteredIsOrAs && DMLexer.SEMICOLON == tokenType) {
                return false;
            } else if (DMLexer.LANGUAGE == tokenType) {
                return false;
            }

            offset++;
        }

        return false;
    }

}
