package jason.typecheck;

import java.io.ByteArrayInputStream;
import java.util.logging.LogManager;

import jason.asSyntax.ASSyntax;
import jason.asunit.TestAgent;
import junit.framework.TestCase;

/** Testes da verificação em execução das crenças e objetivos criados pelos planos e das percepções. */
public class RuntimeBeliefCheckTest extends TestCase {

    @Override
    protected void setUp() throws Exception {
        TypeCheckReport r = new TypeCheckReport();
        TypeTable table = new TypeTable();
        table.add(TypeDirective.parseDecl(ASSyntax.parseLiteral("temperatura(atom, number)"), "t.mas2j", 1, true, r), r);
        table.add(TypeDirective.parseDecl(ASSyntax.parseLiteral("pos(number, number)"), "t.mas2j", 1, true, r), r);
        table.add(TypeDirective.parseDecl(ASSyntax.parseFormula("canto = [pos(0,0), pos(9,9)]"), "t.mas2j", 1, true, r), r);
        table.add(TypeDirective.parseDecl(ASSyntax.parseLiteral("alvo(canto)"), "t.mas2j", 1, true, r), r);
        table.add(TypeDirective.parseDecl(ASSyntax.parseLiteral("mira(canto)"), "t.mas2j", 1, true, r), r);
        assertTrue(r.isOk());
        RuntimeTypeCheck.setGlobal(table);
    }

    @Override
    protected void tearDown() throws Exception {
        RuntimeTypeCheck.clear();
        LogManager.getLogManager().readConfiguration(); // volta à configuração padrão
    }

    /** simula linhas no logging.properties */
    private void options(String props) throws Exception {
        LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(props.getBytes()));
    }

    private static final String CODE =
        "+!ir <- X = 5; +alvo(pos(X, X)); jason.asunit.print(continuei). " +
        "+!certo <- X = 9; +alvo(pos(X, X)); jason.asunit.print(ok). " +
        "-!ir[error(E)] <- jason.asunit.print(falhou, \" \", E). " +
        "+!vai <- X = 5; !mira(pos(X, X)); jason.asunit.print(continuei). " +
        "+!vai2 <- X = 5; !!mira(pos(X, X)); jason.asunit.print(continuei). " +
        "+!vai_certo <- X = 9; !mira(pos(X, X)); jason.asunit.print(ok). " +
        "+!mira(P) <- jason.asunit.print(mirando, \" \", P). " +
        "-!vai[error(E)] <- jason.asunit.print(falhou, \" \", E). " +
        "-!vai2[error(E)] <- jason.asunit.print(falhou, \" \", E). " +
        "+!rem <- X = 5; -alvo(pos(X, X)); jason.asunit.print(continuei). " +
        "-!rem[error(E)] <- jason.asunit.print(falhou, \" \", E). " +
        "+!troca <- X = 5; -+alvo(pos(X, X)); jason.asunit.print(continuei). " +
        "-!troca[error(E)] <- jason.asunit.print(falhou, \" \", E). " +
        "+!pergunta <- X = 5; ?alvo(pos(X, X)); jason.asunit.print(continuei). " +
        "-!pergunta[error(E)] <- jason.asunit.print(falhou, \" \", E). ";

    public void testWarnIsDefault() {
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("ir");
        ag.assertPrint("continuei", 10);       // avisa, mas adiciona e continua
        ag.assertBel("alvo(pos(5,5))", 1);
    }

    public void testCorrectBelief() {
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("certo");
        ag.assertPrint("ok", 10);
        ag.assertBel("alvo(pos(9,9))", 1);
    }

    public void testFail() throws Exception {
        options("jason.typecheck.beliefs.onfail = fail");
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("ir");
        ag.assertPrint("falhou type_error", 10); // o +alvo falha e o plano falha
        ag.assertBel("not alvo(pos(5,5))", 1);
    }

    public void testDisabled() throws Exception {
        options("jason.typecheck.beliefs.onfail = fail\njason.typecheck.beliefs = false");
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("ir");
        ag.assertPrint("continuei", 10);
    }

    public void testGoalWarnIsDefault() {
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("vai");
        ag.assertPrint("mirando pos(5,5)", 10);   // avisa, mas cria o objetivo
        ag.assertPrint("continuei", 10);
    }

    public void testCorrectGoal() {
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("vai_certo");
        ag.assertPrint("mirando pos(9,9)", 10);
        ag.assertPrint("ok", 10);
    }

    public void testGoalFail() throws Exception {
        options("jason.typecheck.goals.onfail = fail");
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("vai");
        ag.assertPrint("falhou type_error", 10);  // o objetivo não é criado e o plano falha
        ag.addGoal("vai2");                       // !! também
        ag.assertPrint("falhou type_error", 10);
    }

    public void testGoalsDisabled() throws Exception {
        options("jason.typecheck.goals.onfail = fail\njason.typecheck.goals = false");
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("vai");
        ag.assertPrint("continuei", 10);
    }

    public void testFromMessageIsSkipped() throws Exception {
        // crenças e objetivos de mensagens (source de outro agente) já foram conferidos na chegada
        options("jason.typecheck.beliefs.onfail = fail\njason.typecheck.goals.onfail = fail");
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        assertNull(RuntimeTypeCheck.checkBelief(ag.getTS(), ASSyntax.parseLiteral("alvo(pos(5,5))[source(bob)]"), null));
        assertNull(RuntimeTypeCheck.checkGoal(ag.getTS(), ASSyntax.parseLiteral("mira(pos(5,5))[source(bob)]"), null));
        assertNotNull(RuntimeTypeCheck.checkBelief(ag.getTS(), ASSyntax.parseLiteral("alvo(pos(5,5))[source(self)]"), null));
        assertNotNull(RuntimeTypeCheck.checkGoal(ag.getTS(), ASSyntax.parseLiteral("mira(pos(5,5))[source(self)]"), null));
    }

    public void testRemoval() throws Exception {
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("rem");
        ag.assertPrint("continuei", 10);          // padrão: avisa e remove (não acha nada)
        options("jason.typecheck.beliefs.onfail = fail");
        TestAgent ag2 = new TestAgent("robo2");
        ag2.parseAScode(CODE);
        ag2.addGoal("rem");
        ag2.assertPrint("falhou type_error", 10);
    }

    public void testDelAddKeepsOldBeliefOnFail() throws Exception {
        options("jason.typecheck.beliefs.onfail = fail");
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode("alvo(pos(0,0)). " + CODE);
        ag.addGoal("troca");
        ag.assertPrint("falhou type_error", 10);
        ag.assertBel("alvo(pos(0,0))", 1);         // a antiga NÃO foi removida
        ag.assertBel("not alvo(pos(5,5))", 1);
    }

    public void testQuery() throws Exception {
        TestAgent ag = new TestAgent("robo");
        ag.parseAScode(CODE);
        ag.addGoal("pergunta");
        ag.assertPrint("falhou test_goal_failed", 10); // padrão: avisa, consulta e não acha (falha normal do Jason)
        options("jason.typecheck.goals.onfail = fail");
        TestAgent ag2 = new TestAgent("robo2");
        ag2.parseAScode(CODE);
        ag2.addGoal("pergunta");
        ag2.assertPrint("falhou type_error", 10);  // fail: falha por tipo, sem consultar
    }

    private static final String ASK =
        "+!perg <- .send(maria, askOne, alvo(_), R); jason.asunit.print(resposta, \" \", R). " +
        "-!perg[error(E)] <- jason.asunit.print(falhou, \" \", E). ";

    public void testAskAnswerWarn() {
        TestAgent bob = new TestAgent("bob"), maria = new TestAgent("maria");
        bob.parseAScode(ASK);
        maria.parseAScode("alvo(pos(5,5)).");     // maria responde com um valor que não é canto
        bob.addGoal("perg");
        bob.assertIdle(10);
        maria.assertIdle(10);
        bob.assertPrint("resposta alvo(pos(5,5))", 10); // padrão: avisa e aceita
    }

    public void testAskAnswerReject() throws Exception {
        options("jason.typecheck.runtime.onfail = reject");
        TestAgent bob = new TestAgent("bob"), maria = new TestAgent("maria");
        bob.parseAScode(ASK);
        maria.parseAScode("alvo(pos(5,5)).");
        bob.addGoal("perg");
        bob.assertIdle(10);
        maria.assertIdle(10);
        bob.assertPrint("falhou type_error", 10);  // o plano que perguntou falha
    }

    public void testAskAnswerCorrect() throws Exception {
        options("jason.typecheck.runtime.onfail = reject");
        TestAgent bob = new TestAgent("bob"), maria = new TestAgent("maria");
        bob.parseAScode(ASK);
        maria.parseAScode("alvo(pos(9,9)).");
        bob.addGoal("perg");
        bob.assertIdle(10);
        maria.assertIdle(10);
        bob.assertPrint("resposta alvo(pos(9,9))", 10);
    }

    public void testPercept() throws Exception {
        TestAgent ag = new TestAgent("robo");
        assertTrue(RuntimeTypeCheck.acceptPercept(ag, ASSyntax.parseLiteral("temperatura(forno, 80)")));
        assertTrue(RuntimeTypeCheck.acceptPercept(ag, ASSyntax.parseLiteral("temperatura(forno, \"quente\")"))); // warn: aceita
        options("jason.typecheck.beliefs.onfail = fail");
        assertTrue(RuntimeTypeCheck.acceptPercept(ag, ASSyntax.parseLiteral("temperatura(forno, 80)")));
        assertFalse(RuntimeTypeCheck.acceptPercept(ag, ASSyntax.parseLiteral("temperatura(forno, \"quente\")"))); // descartada
    }
}
