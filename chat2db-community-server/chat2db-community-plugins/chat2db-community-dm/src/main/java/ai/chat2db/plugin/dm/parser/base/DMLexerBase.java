package ai.chat2db.plugin.dm.parser.base;

import org.antlr.v4.runtime.*;

public abstract class DMLexerBase extends Lexer
{
    public DMLexerBase self;

    public DMLexerBase(CharStream input)
    {
        super(input);
        self = this;
    }

    protected boolean IsNewlineAtPos(int pos)
    {
        int la = _input.LA(pos);
        return la == -1 || la == '\n';
    }
}
