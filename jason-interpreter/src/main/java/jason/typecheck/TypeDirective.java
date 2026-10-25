package jason.typecheck;

import java.util.ArrayList;
import java.util.List;

import jason.asSemantics.Agent;
import jason.asSyntax.Literal;
import jason.asSyntax.Pred;
import jason.asSyntax.SourceInfo;
import jason.asSyntax.Term;
import jason.asSyntax.directives.DefaultDirective;

/**
 * A diretiva que declara tipos num .asl:
 *
 *     { type(temperatura(atom, number)) }
 *     { type(pos(number, number), localizacao(atom, pos)) }   // várias de uma vez
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
        if (!t.isLiteral() || t.isVar() || ((Literal) t).negated()) {
            report.addProblem(loc, t.toString(), "declaração de tipo inválida, use a forma nome(tipo1, tipo2, ...)", null);
            return null;
        }
        Literal l = (Literal) t;
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
}
