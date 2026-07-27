package ai.chat2db.spi.parser.error.strategy;

import org.antlr.v4.runtime.*;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

public class BaseErrorStrategy extends BailErrorStrategy {


    @Override
    protected void reportNoViableAlternative(Parser recognizer, NoViableAltException e) {
        TokenStream tokens = recognizer.getInputStream();
        String msg = "应为<expression> ";
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
                msg += " 得到 " + escapeWSAndQuote(offendingToken.getText());
            }
        }

        recognizer.notifyErrorListeners(e.getOffendingToken(), msg, e);
    }

    @Override
    protected void reportInputMismatch(Parser recognizer, InputMismatchException e) {
        Token offendingToken = e.getOffendingToken();
        int tokenIndex = offendingToken.getTokenIndex();
        String msg = "应为<Expression> 得到: " + getTokenErrorDisplay(offendingToken);
        if (tokenIndex == 1) {
            msg += " 期望: " + e.getExpectedTokens().toString(recognizer.getVocabulary());

        }
        recognizer.notifyErrorListeners(offendingToken, msg, e);
    }

    @Override
    protected void reportUnwantedToken(Parser recognizer) {
        Token t = recognizer.getCurrentToken();
        String tokenName = getTokenErrorDisplay(t);
        String msg = "错误的输入: " + tokenName;
        recognizer.notifyErrorListeners(t, msg, null);
    }

    @Override
    protected void reportMissingToken(Parser recognizer) {
        Token t = recognizer.getCurrentToken();
        String msg = "应为<Expression>";
        recognizer.notifyErrorListeners(t, msg, null);
    }

}
