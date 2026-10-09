package ai.chat2db.spi.parser.error.strategy;

import ai.chat2db.community.tools.util.I18nUtils;
import org.antlr.v4.runtime.*;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

public class BaseErrorStrategy extends BailErrorStrategy {


    @Override
    protected void reportNoViableAlternative(Parser recognizer, NoViableAltException e) {
        TokenStream tokens = recognizer.getInputStream();
        String msg = I18nUtils.getMessage("sqlSyntax.expectedExpression");
        if (Objects.nonNull(tokens)) {
            Token offendingToken = e.getOffendingToken();
            if (Objects.nonNull(offendingToken)) {
                Token startToken = e.getStartToken();
                int startIndex = Math.max(startToken.getTokenIndex(), 0);
                int maxRetry = 10;
                int retryCount = 0;
                if (offendingToken.getType() == Token.EOF) {
                    offendingToken = tokens.get(Math.max(offendingToken.getTokenIndex() - 1, startIndex));
                }
                while (retryCount <= maxRetry && offendingToken.getChannel() != Token.DEFAULT_CHANNEL) {
                    offendingToken = tokens.get(Math.max(offendingToken.getTokenIndex() - 1, startIndex));
                    retryCount++;
                }
            }
            if (Objects.nonNull(offendingToken)
                    && offendingToken.getType() != Token.EOF
                    && StringUtils.isNotBlank(offendingToken.getText())) {
                msg = I18nUtils.getMessage("sqlSyntax.expectedExpressionGot",
                        new Object[]{escapeWSAndQuote(offendingToken.getText())});
            }
        }

        recognizer.notifyErrorListeners(e.getOffendingToken(), msg, e);
    }

    @Override
    protected void reportInputMismatch(Parser recognizer, InputMismatchException e) {
        Token offendingToken = e.getOffendingToken();
        int tokenIndex = offendingToken.getTokenIndex();
        String msg = tokenIndex == 1
                ? I18nUtils.getMessage("sqlSyntax.expectedExpressionGotExpecting", new Object[]{
                getTokenErrorDisplay(offendingToken), e.getExpectedTokens().toString(recognizer.getVocabulary())})
                : I18nUtils.getMessage("sqlSyntax.expectedExpressionGot", new Object[]{getTokenErrorDisplay(offendingToken)});
        recognizer.notifyErrorListeners(offendingToken, msg, e);
    }

    @Override
    protected void reportUnwantedToken(Parser recognizer) {
        Token t = recognizer.getCurrentToken();
        String tokenName = getTokenErrorDisplay(t);
        String msg = I18nUtils.getMessage("sqlSyntax.invalidInput", new Object[]{tokenName});
        recognizer.notifyErrorListeners(t, msg, null);
    }

    @Override
    protected void reportMissingToken(Parser recognizer) {
        Token t = recognizer.getCurrentToken();
        String msg = I18nUtils.getMessage("sqlSyntax.expectedExpression");
        recognizer.notifyErrorListeners(t, msg, null);
    }

}
