package jason.typecheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import jason.asSemantics.Agent;
import jason.asSemantics.Event;
import jason.asSemantics.Message;
import jason.asSemantics.TransitionSystem;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.Atom;
import jason.asSyntax.Literal;
import jason.asSyntax.LiteralImpl;
import jason.asSyntax.StringTermImpl;
import jason.asSyntax.Term;
import jason.asSyntax.Trigger;
import jason.asSyntax.Trigger.TEOperator;
import jason.asSyntax.Trigger.TEType;
import jason.util.Config;

/**
 * Verificação de tipos DURANTE A EXECUÇÃO, nas mensagens recebidas pelos agentes.
 *
 * A verificação estática não consegue ver o que só existe durante a execução:
 *  - .send(X, tell, ...) com o destinatário X decidido durante a execução
 *  - .send(b, tell, C) com o conteúdo C montado durante a execução
 *  - mensagens vindas de fora do sistema
 * Por isso, quando uma mensagem chega, o conteúdo é conferido contra a tabela de tipos
 * de QUEM RECEBE (.mas2j + sobrescritas do .asl do agente que recebe).
 *
 * O que fazer quando o tipo está errado é definido no logging.properties:
 *     jason.typecheck.runtime.onfail = reject   descarta a mensagem e avisa (padrão)
 *     jason.typecheck.runtime.onfail = warn     aceita a mensagem e avisa
 *     jason.typecheck.runtime.onfail = fail     descarta a mensagem, avisa e gera no agente
 *                                               o evento de falha
 *                                               -!kqml_received(Remetente, Tipo, Conteudo, Id)[error(type_error), error_msg("...")]
 *                                               (o agente pode tratar com um plano próprio)
 *     jason.typecheck.runtime = false           desliga esta verificação
 *
 * As tabelas de tipos são registradas pelo {@link StaticTypeCheck} no início da execução
 * (e quando um agente é criado com .create_agent).
 */
public class RuntimeTypeCheck {

    public static final String PROP_RUNTIME = "jason.typecheck.runtime";
    public static final String PROP_ONFAIL  = "jason.typecheck.runtime.onfail";

    public enum OnFail { REJECT, WARN, FAIL }

    /** tipos de mensagem cujo conteúdo é verificado (tellHow, askHow... carregam planos, não crenças) */
    private static final Set<String> CHECKED = Set.of("tell", "untell", "achieve", "unachieve", "signal", "askOne", "askAll");

    private static final Logger logger = Logger.getLogger("jason.typecheck.TypeCheck");

    private static volatile TypeTable global = new TypeTable();
    private static final Map<String, TypeTable> tables = new ConcurrentHashMap<>(); // fonte do .asl -> tabela

    private RuntimeTypeCheck() {}

    /** chamado pelo StaticTypeCheck: as declarações globais (.mas2j) */
    static void setGlobal(TypeTable t) {
        global = t;
    }

    static TypeTable getGlobal() {
        return global;
    }

    /** chamado pelo StaticTypeCheck: a tabela (já juntada com o .mas2j) dos agentes criados a partir de um .asl */
    static void register(String aslSource, TypeTable merged) {
        tables.put(aslSource, merged);
    }

    static boolean isRegistered(String aslSource) {
        return tables.containsKey(aslSource);
    }

    /** esquece todas as tabelas (usado pelos testes) */
    static void clear() {
        global = new TypeTable();
        tables.clear();
    }

    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(property(StaticTypeCheck.PROP_ENABLED))
            && !"false".equalsIgnoreCase(property(PROP_RUNTIME));
    }

    public static OnFail getOnFail() {
        String v = property(PROP_ONFAIL);
        if (v != null) {
            switch (v.trim().toLowerCase()) {
                case "warn": return OnFail.WARN;
                case "fail": return OnFail.FAIL;
            }
        }
        return OnFail.REJECT;
    }

    /**
     * Chamado pelo TransitionSystem quando uma mensagem chega (antes do socAcc).
     * @return true se a mensagem deve ser processada normalmente; false se deve ser descartada
     */
    public static boolean acceptMessage(TransitionSystem ts, Message m, Term content) {
        try {
            return doAcceptMessage(ts, m, content);
        } catch (RuntimeException e) {
            StaticTypeCheck.internalError(e);
            return true;
        }
    }

    private static boolean doAcceptMessage(TransitionSystem ts, Message m, Term content) {
        if (!CHECKED.contains(m.getIlForce()) || !isEnabled())
            return true;
        TypeTable table = tableFor(ts.getAg());
        if (table.isEmpty())
            return true;

        TypeCheckReport report = checkContent(table, content, "mensagem " + m.getIlForce() + " de " + m.getSender());
        if (report.isOk())
            return true;

        OnFail onFail = getOnFail();
        String receiver = ts.getAgArch().getAgName();
        String what;
        switch (onFail) {
            case WARN: what = "mensagem aceita mesmo assim"; break;
            case FAIL: what = "mensagem descartada; gerado o evento de falha -!" + Config.get().getKqmlFunctor(); break;
            default:   what = "mensagem descartada"; break;
        }
        StringBuilder s = new StringBuilder();
        s.append("Verificação de tipos em execução: ").append(receiver)
         .append(" recebeu uma mensagem com tipo errado (").append(what).append(")\n");
        for (TypeCheckReport.Problem p : report.getProblems())
            s.append("\n").append(p);
        logger.warning(s.toString());

        if (onFail == OnFail.FAIL)
            ts.getC().addEvent(failureEvent(ts, m, content, report));
        return onFail == OnFail.WARN;
    }

    /**
     * Confere o conteúdo de uma mensagem contra uma tabela de tipos.
     * O conteúdo pode ser um literal ou uma lista de literais (ex.: .send(b, tell, [a(1), b(2)])).
     */
    static TypeCheckReport checkContent(TypeTable table, Term content, String where) {
        TypeCheckReport report = new TypeCheckReport();
        TypeChecker checker = new TypeChecker(table, report);
        for (Literal l : literals(content))
            checker.checkContent(l, where);
        return report;
    }

    private static List<Literal> literals(Term content) {
        List<Literal> r = new ArrayList<>();
        if (content.isList()) {
            for (Term t : (jason.asSyntax.ListTerm) content)
                if (t.isLiteral() && !t.isVar())
                    r.add((Literal) t);
        } else if (content.isLiteral() && !content.isVar()) {
            r.add((Literal) content);
        }
        return r;
    }

    /** a tabela de quem recebe; se o agente não tem tabela própria, valem as declarações do .mas2j */
    private static TypeTable tableFor(Agent ag) {
        String src = ag.getASLSrc();
        TypeTable t = src == null ? null : tables.get(src);
        return t != null ? t : global;
    }

    /** -!kqml_received(Remetente, Tipo, Conteudo, Id)[error(type_error), error_msg("...")] */
    private static Event failureEvent(TransitionSystem ts, Message m, Term content, TypeCheckReport report) {
        String sender = m.getSender();
        if (sender.equals(ts.getAgArch().getAgName()))
            sender = "self";
        Literal received = new LiteralImpl(Config.get().getKqmlFunctor()).addTerms(
                new Atom(sender),
                ASSyntax.createAtom(m.getIlForce()),
                content,
                new Atom(String.valueOf(m.getMsgId())));
        received.addAnnot(ASSyntax.createStructure("error", new Atom("type_error")));
        received.addAnnot(ASSyntax.createStructure("error_msg", new StringTermImpl(report.getProblems().get(0).getMessage())));
        return new Event(new Trigger(TEOperator.del, TEType.achieve, received));
    }

    private static String property(String name) {
        return LogManager.getLogManager().getProperty(name);
    }
}
