package jason.typecheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import jason.asSemantics.Agent;
import jason.asSemantics.Event;
import jason.asSemantics.Intention;
import jason.asSemantics.Message;
import jason.asSemantics.TransitionSystem;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.Atom;
import jason.asSyntax.Literal;
import jason.asSyntax.LiteralImpl;
import jason.asSyntax.SourceInfo;
import jason.asSyntax.StringTermImpl;
import jason.asSyntax.Term;
import jason.asSyntax.Trigger;
import jason.asSyntax.Trigger.TEOperator;
import jason.asSyntax.Trigger.TEType;
import jason.bb.BeliefBase;
import jason.util.Config;

/**
 * Verificação de tipos DURANTE A EXECUÇÃO: nas mensagens recebidas pelos agentes, nas
 * crenças adicionadas pelos planos (+crença, -+crença), nas percepções do ambiente e nos
 * objetivos criados pelos planos (!objetivo, !!objetivo).
 *
 * A verificação estática não consegue ver o que só existe durante a execução:
 *  - .send(X, tell, ...) com o destinatário X decidido durante a execução
 *  - .send(b, tell, C) com o conteúdo C montado durante a execução
 *  - mensagens vindas de fora do sistema
 * Por isso, quando uma mensagem chega, o conteúdo é conferido contra a tabela de tipos
 * de QUEM RECEBE (.mas2j + sobrescritas do .asl do agente que recebe).
 *
 * O que fazer quando o tipo está errado é definido no logging.properties:
 *     jason.typecheck.runtime.onfail = warn     aceita a mensagem e avisa (padrão, desde 08/11)
 *     jason.typecheck.runtime.onfail = reject   descarta a mensagem e avisa
 *     jason.typecheck.runtime.onfail = fail     descarta a mensagem, avisa e gera no agente
 *                                               o evento de falha
 *                                               -!kqml_received(Remetente, Tipo, Conteudo, Id)[error(type_error), error_msg("...")]
 *                                               (o agente pode tratar com um plano próprio)
 *     jason.typecheck.runtime = false           desliga toda a verificação em execução
 *                                               (mensagens, crenças e percepções)
 *
 * Crenças adicionadas pelos planos e percepções (valores que só existem durante a
 * execução, ex.: +alvo(pos(X, Y)) com X = 5, ou uma percepção vinda do ambiente Java):
 *     jason.typecheck.beliefs.onfail = warn     avisa e adiciona mesmo assim (padrão)
 *     jason.typecheck.beliefs.onfail = fail     o +crença falha (o plano falha com
 *                                               error(type_error)); a percepção é descartada
 *     jason.typecheck.beliefs = false           desliga a verificação de crenças e percepções
 *
 * Objetivos criados pelos planos (!objetivo, !!objetivo):
 *     jason.typecheck.goals.onfail = warn       avisa e cria o objetivo mesmo assim (padrão)
 *     jason.typecheck.goals.onfail = fail       o objetivo não é criado e o plano falha
 *                                               com error(type_error)
 *     jason.typecheck.goals = false             desliga a verificação de objetivos
 *
 * Não entram aqui: crenças e objetivos iniciais (já conferidos pela verificação estática)
 * e crenças e objetivos que vêm de mensagens tell / achieve (o Jason os adiciona com um
 * plano próprio, com ++ e !!, mas eles já foram conferidos quando a mensagem chegou; são
 * reconhecidos pela anotação source de outro agente).
 *
 * As tabelas de tipos são registradas pelo {@link StaticTypeCheck} no início da execução
 * (e quando um agente é criado com .create_agent).
 */
public class RuntimeTypeCheck {

    public static final String PROP_RUNTIME = "jason.typecheck.runtime";
    public static final String PROP_ONFAIL  = "jason.typecheck.runtime.onfail";
    public static final String PROP_BELIEFS = "jason.typecheck.beliefs";
    public static final String PROP_BELIEFS_ONFAIL = "jason.typecheck.beliefs.onfail";
    public static final String PROP_GOALS = "jason.typecheck.goals";
    public static final String PROP_GOALS_ONFAIL = "jason.typecheck.goals.onfail";

    /** percepções com erro já avisadas, por agente (para não repetir o aviso a cada ciclo) */
    private static final java.util.Map<Agent, Set<String>> reportedPercepts = Collections.synchronizedMap(new WeakHashMap<>());

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
                case "reject": return OnFail.REJECT;
                case "fail":   return OnFail.FAIL;
            }
        }
        return OnFail.WARN; // padrão: avisa e aceita (como a estática, as crenças e os objetivos)
    }

    public static boolean beliefsEnabled() {
        return isEnabled() && !"false".equalsIgnoreCase(property(PROP_BELIEFS));
    }

    static boolean failOnBadBelief() {
        String v = property(PROP_BELIEFS_ONFAIL);
        return v != null && "fail".equalsIgnoreCase(v.trim()); // padrão: warn
    }

    public static boolean goalsEnabled() {
        return isEnabled() && !"false".equalsIgnoreCase(property(PROP_GOALS));
    }

    static boolean failOnBadGoal() {
        String v = property(PROP_GOALS_ONFAIL);
        return v != null && "fail".equalsIgnoreCase(v.trim()); // padrão: warn
    }

    /** veio de uma mensagem de outro agente (anotação source(outro)): já foi conferido na chegada */
    private static boolean fromMessage(Literal l) {
        return l.hasSource() && !l.hasSource(BeliefBase.ASelf);
    }

    /**
     * Chamado pelo TransitionSystem quando um plano vai criar um objetivo (!objetivo, !!objetivo).
     * @return null se o objetivo pode ser criado; a mensagem de erro se o plano deve falhar
     *         (só com jason.typecheck.goals.onfail = fail)
     */
    public static String checkGoal(TransitionSystem ts, Literal goal, Intention i) {
        try {
            return doCheckGoal(ts, goal, i);
        } catch (RuntimeException e) {
            StaticTypeCheck.internalError(e);
            return null;
        }
    }

    private static String doCheckGoal(TransitionSystem ts, Literal goal, Intention i) {
        if (!goalsEnabled() || fromMessage(goal))
            return null;
        TypeTable table = tableFor(ts.getAg());
        if (table.isEmpty())
            return null;
        TypeCheckReport report = checkContent(table, withoutAnnots(goal), beliefLocation(goal, i));
        if (report.isOk())
            return null;
        boolean fail = failOnBadGoal();
        warn(ts.getAgArch().getAgName() + " criou um objetivo com tipo errado ("
                + (fail ? "o objetivo não foi criado e o plano falhou" : "objetivo criado mesmo assim") + ")", report);
        return fail ? report.getProblems().get(0).getMessage() : null;
    }

    /**
     * Chamado pelo TransitionSystem quando um plano vai adicionar uma crença (+crença, -+crença).
     * @return null se a crença pode ser adicionada; a mensagem de erro se o +crença deve falhar
     *         (só com jason.typecheck.beliefs.onfail = fail)
     */
    public static String checkBelief(TransitionSystem ts, Literal bel, Intention i) {
        try {
            return doCheckBelief(ts, bel, i);
        } catch (RuntimeException e) {
            StaticTypeCheck.internalError(e);
            return null;
        }
    }

    private static String doCheckBelief(TransitionSystem ts, Literal bel, Intention i) {
        if (!beliefsEnabled() || fromMessage(bel))
            return null;
        TypeTable table = tableFor(ts.getAg());
        if (table.isEmpty())
            return null;
        TypeCheckReport report = checkContent(table, withoutAnnots(bel), beliefLocation(bel, i));
        if (report.isOk())
            return null;
        boolean fail = failOnBadBelief();
        warn(ts.getAgArch().getAgName() + " adicionou uma crença com tipo errado ("
                + (fail ? "a crença não foi adicionada e o plano falhou" : "crença adicionada mesmo assim") + ")", report);
        return fail ? report.getProblems().get(0).getMessage() : null;
    }

    /**
     * Chamado pelo Agent (buf) antes de adicionar uma percepção do ambiente.
     * @return true se a percepção deve ser adicionada
     */
    public static boolean acceptPercept(Agent ag, Literal percept) {
        try {
            return doAcceptPercept(ag, percept);
        } catch (RuntimeException e) {
            StaticTypeCheck.internalError(e);
            return true;
        }
    }

    private static boolean doAcceptPercept(Agent ag, Literal percept) {
        if (!beliefsEnabled())
            return true;
        TypeTable table = tableFor(ag);
        if (table.isEmpty())
            return true;
        TypeCheckReport report = checkContent(table, withoutAnnots(percept), "percepção do ambiente");
        if (report.isOk())
            return true;
        boolean fail = failOnBadBelief();
        // com fail, a percepção descartada volta a cada ciclo: avisa só na primeira vez
        if (reportedPercepts.computeIfAbsent(ag, k -> Collections.synchronizedSet(new HashSet<>())).add(percept.toString())) {
            String name = ag.getTS() != null ? ag.getTS().getAgArch().getAgName() : "agente";
            warn(name + " recebeu uma percepção com tipo errado ("
                    + (fail ? "percepção descartada" : "percepção aceita mesmo assim") + ")", report);
        }
        return !fail;
    }

    /** cópia sem anotações ([source(self)], [source(percept)]), para a mensagem ficar limpa */
    private static Literal withoutAnnots(Literal l) {
        Literal c = l.copy();
        c.clearAnnots();
        return c;
    }

    /** onde a crença (ou objetivo) foi criada, ex.: robo.asl:4 (a linha do +crença no plano) */
    private static String beliefLocation(Literal bel, Intention i) {
        SourceInfo s = bel.getSrcInfo();
        if ((s == null || s.getSrcFile() == null) && i != null && !i.isFinished() && i.peek().getPlan() != null)
            s = i.peek().getPlan().getSrcInfo();
        if (s == null || s.getSrcFile() == null)
            return "crença adicionada por um plano";
        return TypeCheckReport.shortFile(s.getSrcFile()) + (s.getBeginSrcLine() > 0 ? ":" + s.getBeginSrcLine() : "");
    }

    private static void warn(String header, TypeCheckReport report) {
        StringBuilder s = new StringBuilder("Verificação de tipos em execução: ").append(header).append("\n");
        for (TypeCheckReport.Problem p : report.getProblems())
            s.append("\n").append(p);
        logger.warning(s.toString());
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
