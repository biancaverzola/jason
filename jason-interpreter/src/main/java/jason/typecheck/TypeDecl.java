package jason.typecheck;

import java.util.List;

import jason.asSyntax.PredicateIndicator;

/**
 * Uma declaração de tipo, por exemplo: temperatura(atom, number).
 * Também pode ser a declaração de um tipo enumerado, por exemplo: cor = [amarela, azul]
 * (nesse caso, getEnumValues() não é null).
 * Guarda também onde ela foi escrita, para as mensagens de erro.
 */
public class TypeDecl {

    private final String     functor;
    private final List<Type> argTypes;
    private final String     srcFile;
    private final int        srcLine;
    private final boolean    fromMas2j; // true = declarada no .mas2j (vale para todos os agentes)
    private final List<String> enumValues; // só para tipo enumerado: os átomos aceitos

    public TypeDecl(String functor, List<Type> argTypes, String srcFile, int srcLine, boolean fromMas2j) {
        this.functor   = functor;
        this.argTypes  = List.copyOf(argTypes);
        this.srcFile   = srcFile;
        this.srcLine   = srcLine;
        this.fromMas2j = fromMas2j;
        this.enumValues = null;
    }

    /** declaração de tipo enumerado, ex.: cor = [amarela, azul] */
    public TypeDecl(String name, List<String> enumValues, String srcFile, int srcLine, boolean fromMas2j, boolean isEnum) {
        this.functor    = name;
        this.argTypes   = List.of();
        this.srcFile    = srcFile;
        this.srcLine    = srcLine;
        this.fromMas2j  = fromMas2j;
        this.enumValues = List.copyOf(enumValues);
    }

    public boolean      isEnum()        { return enumValues != null; }
    public List<String> getEnumValues() { return enumValues; }
    /** o tipo que esta declaração enumerada define */
    public Type         getEnumType()   { return Type.enumOf(functor, enumValues); }

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
        if (isEnum())
            return functor + " = [" + String.join(", ", enumValues) + "]";
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
