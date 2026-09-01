package ai.chat2db.plugin.dm.parser.rule;

import ai.chat2db.spi.parser.AbstractRulePredicate;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

import java.util.List;

public abstract class DMRulePredicate extends AbstractRulePredicate {


    @Override
    public boolean matches(List<Token> tokens, int currentIndex) {
        return true;
    }

    @Override
    public boolean needUpgradeBlock(List<Token> tokens, int currentIndex) {
        return false;
    }

    @Override
    public boolean matches(TokenStream tokenStream) {
        return true;
    }
}
