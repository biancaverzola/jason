package jason.typecheck;

import java.util.List;
import java.util.Objects;

import jason.asSyntax.ListTerm;
import jason.asSyntax.Literal;
import jason.asSyntax.Term;

/**
 * Um tipo do verificador estático.
 *
 * Tipos disponíveis:
 *  - atom, number, string, boolean, list, any
 *  - list(T): lista cujos elementos são do tipo T
 *  - nome de um predicado declarado (ex.: pos, quando existe a declaração pos(number, number))
 *  - nome de um tipo enumerado: só aceita os valores listados, que são todos átomos,
 *    todos números, todos strings ou todos estruturas com o mesmo nome (sem variáveis),
 *    ex.: cor = [amarela, azul], nota = [1, 2, 3], tamanho = ["P", "M", "G"],
 *    canto = [pos(0,0), pos(9,9)]
 */
public final class Type {

    public enum Kind { ATOM, NUMBER, STRING, BOOLEAN, LIST, ANY, STRUCT, ENUM }

    public static final Type ATOM    = new Type(Kind.ATOM,    null, null, null, null);
    public static final Type NUMBER  = new Type(Kind.NUMBER,  null, null, null, null);
    public static final Type STRING  = new Type(Kind.STRING,  null, null, null, null);
    public static final Type BOOLEAN = new Type(Kind.BOOLEAN, null, null, null, null);
    public static final Type LIST    = new Type(Kind.LIST,    null, null, null, null); // list sem tipo dos elementos
    public static final Type ANY     = new Type(Kind.ANY,     null, null, null, null);

    private final Kind   kind;
    private final String structName; // só para STRUCT e ENUM (o nome do tipo)
    private final Type   elemType;   // só para list(T)
    private final List<String> enumValues; // só para ENUM: os valores aceitos (como texto, ver enumKey)
    private final Type   enumBase;   // só para ENUM: o tipo dos valores (atom, number, string ou a estrutura, ex.: pos)

    private Type(Kind kind, String structName, Type elemType, List<String> enumValues, Type enumBase) {
        this.kind       = kind;
        this.structName = structName;
        this.elemType   = elemType;
        this.enumValues = enumValues;
        this.enumBase   = enumBase;
    }

    public static Type listOf(Type elem) {
        return new Type(Kind.LIST, null, elem, null, null);
    }

    public static Type struct(String name) {
        return new Type(Kind.STRUCT, name, null, null, null);
    }

    /**
     * tipo enumerado, ex.: cor = [amarela, azul]
     * @param base   o tipo dos valores: atom, number, string ou uma estrutura (ex.: pos)
     * @param values os valores, como texto (ver enumKey)
     */
    public static Type enumOf(String name, Type base, List<String> values) {
        return new Type(Kind.ENUM, name, null, List.copyOf(values), base);
    }

    /**
     * O texto usado para comparar um valor com os valores de um enumerado:
     * amarela, 3, 2.5, -1, "P", pos(0,0). Retorna null se o termo não pode ser valor de enumerado.
     */
    public static String enumKey(Term t) {
        if (t.isNumeric() && !t.isArithExpr())
            return t.toString();
        if (t.isString())
            return t.toString();
        if (t.isLiteral() && !t.isVar() && !t.isList() && !t.isArithExpr()) {
            Literal l = (Literal) t;
            if (l.getArity() == 0)
                return l.getFunctor();
            if (t.isGround() && !l.hasAnnot() && !l.negated())   // estrutura sem variáveis, ex.: pos(0,0)
                return l.toString();
        }
        return null;
    }

    public Kind   getKind()       { return kind; }
    public String getStructName() { return structName; }
    /** tipo dos elementos de list(T), ou null se for só "list" */
    public Type   getElemType()   { return elemType; }

    public boolean isAny()    { return kind == Kind.ANY; }
    public boolean isList()   { return kind == Kind.LIST; }
    public boolean isStruct() { return kind == Kind.STRUCT; }
    public boolean isEnum()   { return kind == Kind.ENUM; }

    /** os valores aceitos por um tipo enumerado */
    public List<String> getEnumValues() { return enumValues; }
    /** o tipo dos valores de um enumerado: atom, number, string ou uma estrutura (ex.: pos) */
    public Type getEnumBase() { return enumBase; }

    /** ex.: "cor (amarela, azul, vermelha)", para as mensagens */
    public String describeEnum() {
        return structName + " (" + String.join(", ", enumValues) + ")";
    }

    /**
     * Converte um termo escrito numa declaração (ex.: number, list(pos)) em um Type.
     * Retorna null se o termo não for um nome de tipo válido.
     * Nomes que não são tipos básicos viram STRUCT; a TypeTable verifica depois
     * se esse nome foi mesmo declarado.
     */
    public static Type fromTerm(Term t) {
        if (!t.isLiteral() || t.isVar())
            return null;
        Literal l = (Literal) t;
        if (l.negated() || l.hasAnnot())
            return null;
        String name = l.getFunctor();
        if (l.getArity() == 0) {
            switch (name) {
                case "atom":    return ATOM;
                case "number":  return NUMBER;
                case "string":  return STRING;
                case "boolean": return BOOLEAN;
                case "list":    return LIST;
                case "any":     return ANY;
                default:        return struct(name);
            }
        }
        if (l.getArity() == 1 && name.equals("list")) {
            Type elem = fromTerm(l.getTerm(0));
            return elem == null ? null : listOf(elem);
        }
        return null;
    }

    /**
     * Diz se um valor do tipo 'actual' pode ser usado onde se espera 'expected'.
     * É permissivo de propósito: na dúvida, aceita (para não acusar código correto).
     */
    public static boolean accepts(Type expected, Type actual) {
        if (expected.isAny() || actual.isAny())
            return true;
        switch (expected.kind) {
            case ATOM:
                // true e false são átomos em Jason; os valores de um enumerado de átomos também
                return actual.kind == Kind.ATOM || actual.kind == Kind.BOOLEAN
                    || (actual.kind == Kind.ENUM && actual.enumBase.kind == Kind.ATOM);
            case NUMBER:
            case STRING:
                // um valor de nota = [1, 2, 3] é um number; de tamanho = ["P", "M"], uma string
                return actual.kind == expected.kind || (actual.kind == Kind.ENUM && actual.enumBase.kind == expected.kind);
            case ENUM:
                // um valor qualquer do tipo base é aceito aqui: QUAL valor é conferido onde ele
                // está escrito (ver TypeChecker.checkEnumValue), não pelo tipo
                if (actual.kind == Kind.ENUM)
                    return expected.structName.equals(actual.structName);
                return accepts(expected.enumBase, actual);
            case BOOLEAN:
                return actual.kind == Kind.BOOLEAN;
            case LIST:
                if (actual.kind != Kind.LIST)
                    return false;
                if (expected.elemType == null || actual.elemType == null)
                    return true;
                return accepts(expected.elemType, actual.elemType);
            case STRUCT:
                // um valor de canto = [pos(0,0), pos(9,9)] também é um pos
                if (actual.kind == Kind.ENUM)
                    return accepts(expected, actual.enumBase);
                return actual.kind == Kind.STRUCT && expected.structName.equals(actual.structName);
            default:
                return expected.kind == actual.kind;
        }
    }

    /** dois tipos são compatíveis se algum deles aceita o outro */
    public static boolean compatible(Type a, Type b) {
        return accepts(a, b) || accepts(b, a);
    }

    /** dos dois tipos (compatíveis), retorna o que tem mais informação */
    public static Type moreSpecific(Type a, Type b) {
        if (a.isAny()) return b;
        if (b.isAny()) return a;
        if (a.kind == Kind.ATOM && b.kind == Kind.BOOLEAN) return b;
        if (b.kind == Kind.ENUM && a.kind != Kind.ENUM) return b; // ex.: number e nota -> nota
        if (a.kind == Kind.LIST && a.elemType == null) return b;
        return a;
    }

    /** descreve o tipo de um termo constante, para as mensagens de erro */
    static String describeValue(Term t) {
        if (t.isString())  return "string";
        if (t.isNumeric()) return "number";
        if (t.isList())    return "list " + ((ListTerm) t);
        if (t.isLiteral() && ((Literal) t).getArity() > 0) return "estrutura " + t;
        return "atom " + t;
    }

    @Override
    public String toString() {
        switch (kind) {
            case ATOM:    return "atom";
            case NUMBER:  return "number";
            case STRING:  return "string";
            case BOOLEAN: return "boolean";
            case ANY:     return "any";
            case STRUCT:  return structName;
            case ENUM:    return structName;
            case LIST:    return elemType == null ? "list" : "list(" + elemType + ")";
        }
        return kind.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Type)) return false;
        Type t = (Type) o;
        return kind == t.kind && Objects.equals(structName, t.structName) && Objects.equals(elemType, t.elemType)
            && Objects.equals(enumValues, t.enumValues) && Objects.equals(enumBase, t.enumBase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, structName, elemType, enumValues);
    }
}
