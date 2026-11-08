package jason.typecheck;

import java.util.ArrayList;
import java.util.List;

import jason.asSemantics.Agent;
import jason.asSyntax.Literal;
import jason.asSemantics.Unifier;
import jason.asSyntax.ArithExpr;
import jason.asSyntax.InternalActionLiteral;
import jason.asSyntax.ListTerm;
import jason.asSyntax.Pred;
import jason.asSyntax.RelExpr;
import jason.asSyntax.SourceInfo;
import jason.asSyntax.Term;
import jason.asSyntax.directives.DefaultDirective;

/**
 * A diretiva que declara tipos num .asl:
 *
 *     { type(temperatura(atom, number)) }
 *     { type(pos(number, number), localizacao(atom, pos)) }   // várias de uma vez
 *     { type(cor = [amarela, azul, vermelha]) }               // tipo enumerado
 *
 * Durante a execução normal do Jason a diretiva não faz nada (é ignorada).
 * Só quando o verificador está lendo os .asl (ver {@link StaticTypeCheck}) é que as
 * declarações são guardadas. Por isso um programa com declarações roda normalmente
 * mesmo com a verificação desligada.
 *
 * Como funciona: o verificador "liga a coleta" (startCollecting) antes de fazer o parse
 * de um agente e "desliga" (stopCollecting) depois. Enquanto a coleta está ligada, cada
 * diretiva type encontrada pelo parser (inclusive em arquivos incluídos com
 * { include(...) }) adiciona suas declarações à lista.
 */
public class TypeDirective extends DefaultDirective {

    public static final String NAME = "type";

    private static class Collector {
        final List<TypeDecl>  decls = new ArrayList<>();
        final TypeCheckReport report;
        Collector(TypeCheckReport report) { this.report = report; }
    }

    // ThreadLocal: cada thread tem a sua coleta (o parse é feito na mesma thread do verificador)
    private static final ThreadLocal<Collector> collector = new ThreadLocal<>();

    static void startCollecting(TypeCheckReport report) {
        collector.set(new Collector(report));
    }

    static List<TypeDecl> stopCollecting() {
        Collector c = collector.get();
        collector.remove();
        return c == null ? List.of() : c.decls;
    }

    @Override
    public Agent process(Pred directive, Agent outerContent, Agent innerContent) {
        Collector c = collector.get();
        if (c == null)
            return null; // execução normal: ignora a declaração

        SourceInfo src = directive.getSrcInfo();
        String file = src != null ? src.getSrcFile() : (outerContent != null ? outerContent.getASLSrc() : null);
        int    line = src != null ? src.getBeginSrcLine() : 0;

        if (directive.getArity() == 0) {
            c.report.addProblem(TypeCheckReport.shortFile(file) + ":" + line, "{ " + directive + " }",
                    "a diretiva type precisa de pelo menos uma declaração, ex.: { type(temperatura(atom, number)) }", null);
            return null;
        }
        for (Term t : directive.getTerms()) {
            TypeDecl d = parseDecl(t, file, line, false, c.report);
            if (d != null)
                c.decls.add(d);
        }
        return null;
    }

    /**
     * Converte um termo como temperatura(atom, number) em uma TypeDecl.
     * Usado tanto pela diretiva quanto pelo bloco types do .mas2j.
     * Retorna null (e registra um problema) se a declaração for inválida.
     */
    public static TypeDecl parseDecl(Term t, String file, int line, boolean fromMas2j, TypeCheckReport report) {
        String loc = TypeCheckReport.shortFile(file) + ":" + line;
        if (t instanceof RelExpr)
            return parseEnumDecl((RelExpr) t, file, line, fromMas2j, report);
        if (!t.isLiteral() || t.isVar() || ((Literal) t).negated()) {
            report.addProblem(loc, t.toString(), "declaração de tipo inválida, use a forma nome(tipo1, tipo2, ...)", null);
            return null;
        }
        Literal l = (Literal) t;
        if (l instanceof InternalActionLiteral || l.getFunctor().startsWith(".")) {
            // ex.: .print(string): os argumentos das ações internas não são verificados (nível 3)
            report.addProblem(loc, t.toString(), "não dá para declarar o tipo de uma ação interna (" + l.getFunctor()
                    + "); o verificador não confere os argumentos das ações internas", null);
            return null;
        }
        List<Type> args = new ArrayList<>();
        for (int i = 0; i < l.getArity(); i++) {
            Type at = Type.fromTerm(l.getTerm(i));
            if (at == null) {
                report.addProblem(loc, t.toString(),
                        "argumento " + (i + 1) + ": '" + l.getTerm(i) + "' não é um tipo válido", null);
                return null;
            }
            args.add(at);
        }
        return new TypeDecl(l.getFunctor(), args, file, line, fromMas2j);
    }

    private static final String ENUM_FORM = "tipo enumerado inválido, use a forma nome = [valor1, valor2, ...], com valores do mesmo tipo "
            + "(todos átomos, todos números ou todos strings), ex.: cor = [amarela, azul], nota = [1, 2, 3], tamanho = [\"P\", \"M\"]";

    /** cor = [amarela, azul, vermelha]   nota = [1, 2, 3]   tamanho = ["P", "M", "G"] */
    private static TypeDecl parseEnumDecl(RelExpr r, String file, int line, boolean fromMas2j, TypeCheckReport report) {
        String loc  = TypeCheckReport.shortFile(file) + ":" + line;
        String code = r.getLHS() + " = " + r.getRHS();
        Term name = r.getLHS();
        Term vals = r.getRHS();
        boolean ok = r.getOp() == RelExpr.RelationalOp.unify
                && isPlainAtom(name)
                && Type.fromTerm(name) != null && Type.fromTerm(name).isStruct()  // não pode ser number, atom, ...
                && vals.isList() && !((ListTerm) vals).isEmpty();
        List<String> values = new ArrayList<>();
        Type.Kind base = null;
        if (ok) {
            ListTerm lt = (ListTerm) vals;
            if (lt.getTail() != null)   // [a|T]
                ok = false;
            for (Term v : lt.getAsList()) {
                v = evalNumber(v);      // ex.: -1 é lido como a conta -(1)
                Type.Kind k = enumValueKind(v);
                if (k == null || (base != null && k != base)) { ok = false; break; } // inválido ou misturado
                base = k;
                String key = Type.enumKey(v);
                if (!values.contains(key))
                    values.add(key);
            }
        }
        if (!ok) {
            report.addProblem(loc, code, ENUM_FORM, null);
            return null;
        }
        return new TypeDecl(((Literal) name).getFunctor(), base, values, file, line, fromMas2j);
    }

    /** o tipo de um valor de enumerado: ATOM, NUMBER, STRING, ou null se não pode ser valor */
    private static Type.Kind enumValueKind(Term v) {
        if (v.isNumeric() && !v.isArithExpr()) return Type.Kind.NUMBER;
        if (v.isString())                      return Type.Kind.STRING;
        if (isPlainAtom(v))                    return Type.Kind.ATOM;
        return null;
    }

    /** se o termo é uma conta sem variáveis (ex.: -1), retorna o resultado; senão, o próprio termo */
    static Term evalNumber(Term t) {
        if (t instanceof ArithExpr && t.isGround()) {
            try {
                return ((ArithExpr) t).capply(new Unifier());
            } catch (Exception e) {
                // não conseguiu calcular: fica com o termo
            }
        }
        return t;
    }

    /** um átomo simples, ex.: amarela (não variável, número, string ou estrutura) */
    static boolean isPlainAtom(Term t) {
        if (t == null || t.isVar() || !t.isLiteral() || t.isList() || t.isArithExpr() || t instanceof RelExpr)
            return false;
        Literal l = (Literal) t;
        return l.getArity() == 0 && !l.negated() && !l.hasAnnot();
    }
}
