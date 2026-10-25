package jason.typecheck;

import java.util.List;

import jason.asSyntax.PredicateIndicator;

/**
 * Uma declaração de tipo, por exemplo: temperatura(atom, number).
 * Guarda também onde ela foi escrita, para as mensagens de erro.
 */
public class TypeDecl {

    private final String     functor;
    private final List<Type> argTypes;
    private final String     srcFile;
    private final int        srcLine;
    private final boolean    fromMas2j; // true = declarada no .mas2j (vale para todos os agentes)

    public TypeDecl(String functor, List<Type> argTypes, String srcFile, int srcLine, boolean fromMas2j) {
        this.functor   = functor;
        this.argTypes  = List.copyOf(argTypes);
        this.srcFile   = srcFile;
        this.srcLine   = srcLine;
        this.fromMas2j = fromMas2j;
    }

    public String     getFunctor()     { return functor; }
    public int        getArity()       { return argTypes.size(); }
    public List<Type> getArgTypes()    { return argTypes; }
    public Type       getArgType(int i){ return argTypes.get(i); }
    public String     getSrcFile()     { return srcFile; }
    public int        getSrcLine()     { return srcLine; }
    public boolean    isFromMas2j()    { return fromMas2j; }

    /** nome/aridade, ex.: temperatura/2 */
    public PredicateIndicator getPI() {
        return new PredicateIndicator(functor, getArity());
    }

    /** onde foi declarada, ex.: cozinha.mas2j:5 */
    public String getLocation() {
        return TypeCheckReport.shortFile(srcFile) + (srcLine > 0 ? ":" + srcLine : "");
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(functor);
        if (!argTypes.isEmpty()) {
            s.append("(");
            for (int i = 0; i < argTypes.size(); i++) {
                if (i > 0) s.append(", ");
                s.append(argTypes.get(i));
            }
            s.append(")");
        }
        return s.toString();
    }
}
