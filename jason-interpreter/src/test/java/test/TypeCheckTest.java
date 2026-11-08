package test;

import java.io.StringReader;

import jason.asSemantics.Agent;
import jason.asSyntax.ASSyntax;
import jason.mas2j.MAS2JProject;
import jason.mas2j.parser.mas2j;
import jason.typecheck.StaticTypeCheck;
import jason.typecheck.TypeCheckReport;
import junit.framework.TestCase;

/** Testes do verificador estático de tipos (pacote jason.typecheck). */
public class TypeCheckTest extends TestCase {

    /** verifica o código e retorna o relatório */
    private TypeCheckReport check(String code) {
        return StaticTypeCheck.checkCode(code, null);
    }

    /** verifica o código usando as declarações do .mas2j informado */
    private TypeCheckReport check(String mas2j, String code) throws Exception {
        MAS2JProject project = new mas2j(new StringReader(mas2j)).mas();
        return StaticTypeCheck.checkCode(code, project);
    }

    /** confere a quantidade de problemas (e mostra o relatório se não bater) */
    private void assertProblems(int expected, TypeCheckReport r) {
        assertEquals(r.format(), expected, r.getProblems().size());
    }

    /** confere que algum problema contém o texto */
    private void assertHasProblem(String text, TypeCheckReport r) {
        for (TypeCheckReport.Problem p : r.getProblems())
            if (p.toString().contains(text))
                return;
        fail("esperava um problema contendo '" + text + "', mas o relatório foi:\n" + r.format());
    }

    // ---- crenças e objetivos iniciais ------------------------------------

    public void testCodeWithoutDeclarations() {
        assertTrue(check("temperatura(forno, \"duzentos\"). !g. +!g <- x(1).").isOk());
    }

    public void testCorrectBelief() {
        assertTrue(check("{ type(temperatura(atom, number)) } temperatura(forno, 200).").isOk());
    }

    public void testWrongTypeInBelief() {
        TypeCheckReport r = check("{ type(temperatura(atom, number)) } temperatura(forno, \"duzentos\").");
        assertProblems(1, r);
        assertHasProblem("argumento 2: esperado number, encontrado string", r);
    }

    public void testWrongTypeInInitialGoal() {
        assertProblems(1, check("{ type(cozinhar(atom)) } !cozinhar(10)."));
    }

    public void testUndeclaredArityIsNotChecked() {
        // decisão 6a (opção B): pos/3 não foi declarado, então não é verificado
        assertTrue(check("{ type(pos(number, number)) } pos(1, 2). pos(robo, 1, 2).").isOk());
    }

    public void testAnnotationsAreIgnored() {
        assertTrue(check("{ type(idade(number)) } idade(30)[source(bob), fonte(\"x\")].").isOk());
    }

    // ---- tipos -------------------------------------------------------------

    public void testBoolean() {
        assertTrue(check("{ type(ligado(boolean)) } ligado(true). ligado(false).").isOk());
        assertProblems(1, check("{ type(ligado(boolean)) } ligado(sim)."));
        // true e false também são átomos
        assertTrue(check("{ type(estado(atom)) } estado(true).").isOk());
    }

    public void testAny() {
        assertTrue(check("{ type(valor(any)) } valor(1). valor(a). valor(\"s\"). valor([1]). valor(f(x)).").isOk());
    }

    public void testStructType() {
        String decls = "{ type(pos(number, number), localizacao(atom, pos)) } ";
        assertTrue(check(decls + "localizacao(robo, pos(3, 5)).").isOk());
        assertHasProblem("esperado pos, encontrado number", check(decls + "localizacao(robo, 7)."));
        assertHasProblem("encontrado a estrutura ponto(1,2)", check(decls + "localizacao(robo, ponto(1, 2))."));
        // os argumentos da estrutura também são verificados
        assertHasProblem("esperado number, encontrado string", check(decls + "localizacao(robo, pos(3, \"cinco\"))."));
    }

    public void testList() {
        assertTrue(check("{ type(itens(list)) } itens([1, a, \"b\"]).").isOk());
        assertProblems(1, check("{ type(itens(list)) } itens(a)."));
    }

    public void testListOf() {
        String decls = "{ type(pos(number, number), caminho(list(pos))) } ";
        assertTrue(check(decls + "caminho([pos(1,2), pos(3,4)]). caminho([]).").isOk());
        assertHasProblem("elemento 2: esperado pos, encontrado atom", check(decls + "caminho([pos(1,2), forno])."));
    }

    public void testListThroughVariable() {
        // a lista passa por uma variável antes de ser usada: os elementos continuam sendo conferidos
        String decls = "{ type(notas(list(number))) } ";
        TypeCheckReport r = check(decls + "+!d <- L = [1, \"a\"]; +notas(L).");
        assertProblems(1, r);
        assertHasProblem("elemento 2: esperado number, encontrado string", r);
        // ordem inversa: L já é list(number) quando recebe a lista
        r = check(decls + "+!e <- ?notas(L); L = [1, \"a\"].");
        assertProblems(1, r);
        assertHasProblem("elemento 2: esperado number, encontrado string", r);
        // casos certos
        assertTrue(check(decls + "+!f <- L = [1, 2]; +notas(L).").isOk());
        assertTrue(check(decls + "+!g <- L = [1, \"a\"].").isOk());      // L sem declaração: livre
        assertTrue(check(decls + "+!h <- L = [1, X]; +notas(L); Y = X + 1.").isOk());
        // a variável de dentro da lista ganha o tipo do elemento
        assertProblems(1, check("{ type(notas(list(number)), nome(string)) } +!i <- L = [X]; +notas(L); +nome(X)."));
    }

    // ---- declarações inválidas ------------------------------------------

    public void testUnknownTypeName() {
        assertHasProblem("tipo desconhecido 'cores'", check("{ type(cor(cores)) } cor(azul)."));
    }

    public void testDuplicateDeclaration() {
        assertHasProblem("declaração duplicada", check("{ type(idade(number)) } { type(idade(string)) }"));
        // repetir a mesma declaração não é problema
        assertTrue(check("{ type(idade(number)) } { type(idade(number)) }").isOk());
    }

    public void testSeveralDeclarationsInOneDirective() {
        TypeCheckReport r = check("{ type(a(number), b(string)) } a(\"x\"). b(1).");
        assertProblems(2, r);
    }

    // ---- planos e variáveis (inferência nível 2) ------------------------

    public void testVariableFromContextUsedInBody() {
        // o exemplo da proposta (seção 4.4)
        String code = "{ type(temperatura(atom, number), ligar(atom)) } "
                    + "+!verificar : temperatura(forno, T) <- ligar(T).";
        TypeCheckReport r = check(code);
        assertProblems(1, r);
        assertHasProblem("esperado atom, encontrado number (T vem de temperatura/2", r);
    }

    public void testVariableFromTrigger() {
        assertProblems(1, check("{ type(cozinhar(atom), contar(number)) } +!cozinhar(X) <- contar(X)."));
    }

    public void testEachPlanHasItsOwnVariables() {
        String code = "{ type(a(number), b(string)) } "
                    + "+!p1 : a(X) <- .print(X). "
                    + "+!p2 : b(X) <- .print(X).";
        assertTrue(check(code).isOk());
    }

    public void testWrongConstantInBody() {
        TypeCheckReport r = check("{ type(temperatura(atom, number)) } +!g <- +temperatura(forno, \"baixa\"); -temperatura(forno, x).");
        assertProblems(2, r);
    }

    public void testArithmeticInfersNumber() {
        String code = "{ type(nome(string)) } +!g : nome(N) <- X = N * 2.";
        assertHasProblem("operação N*2: esperado number, encontrado string", check(code));
    }

    public void testArithmeticResultIsNumber() {
        String code = "{ type(temperatura(atom, number), servir(atom)) } "
                    + "+!g : temperatura(forno, T) <- N = T + 10; servir(N).";
        assertHasProblem("esperado atom, encontrado number (N vem de", check(code));
    }

    public void testComparisonWithNumberInfersNumber() {
        String code = "{ type(nome(string)) } +!g : nome(N) & N > 10 <- .print(N).";
        assertProblems(1, check(code));
    }

    public void testComparisonWithoutNumbersInfersNothing() {
        // em Jason, > também compara átomos e strings: não é erro
        String code = "{ type(nome(string)) } +!g : nome(N) & N > \"abc\" <- .print(N).";
        assertTrue(check(code).isOk());
    }

    public void testUnification() {
        String code = "{ type(idade(number)) } +!g <- Z = \"texto\"; +idade(Z).";
        assertHasProblem("Z vem de unificação", check(code));
    }

    public void testIfWhileFor() {
        String decls = "{ type(idade(number), nome(string)) } ";
        assertProblems(1, check(decls + "+!g <- if (idade(A)) { +nome(A) }."));
        assertProblems(1, check(decls + "+!g <- if (true) { .print(x) } else { +idade(\"x\") }."));
        assertProblems(1, check(decls + "+!g <- while (idade(A)) { +nome(A) }."));
        assertProblems(1, check(decls + "+!g <- for (idade(A)) { +nome(A) }."));
    }

    public void testNotAndUnaryMinus() {
        // "not" e o menos unário só têm um lado (não podem quebrar o verificador)
        assertTrue(check("{ type(idade(number)) } +!g : not idade(_) & X = -3 <- Y = -X; +idade(Y).").isOk());
        assertProblems(1, check("{ type(idade(number), nome(string)) } +!g : not idade(A) <- +nome(A)."));
    }

    public void testRule() {
        String code = "{ type(idade(number), nome(string)) } velho(P) :- idade(I) & I > 60 & nome(I).";
        assertProblems(1, check(code));
    }

    public void testInternalActionArgumentsAreNotChecked() {
        // nível 3 (ações internas) e o conteúdo do .send ficaram para depois
        assertTrue(check("{ type(idade(number)) } +!g <- .send(bob, tell, idade(\"x\")); .print(idade(\"y\")).").isOk());
    }

    // ---- .mas2j e sobrescrita ---------------------------------------------

    public void testMas2jDeclarations() throws Exception {
        String mas = "MAS m { agents: a; types { temperatura(atom, number); } }";
        assertTrue(check(mas, "temperatura(forno, 200).").isOk());
        TypeCheckReport r = check(mas, "temperatura(forno, \"x\").");
        assertProblems(1, r);
        assertHasProblem("tipo declarado em: mas2j:1", r);
    }

    public void testAgentOverridesMas2j() throws Exception {
        String mas = "MAS m { agents: a; types { temperatura(atom, number); servir(atom); } }";
        String code = "{ type(temperatura(atom, string)) } temperatura(forno, \"alta\"). servir(10).";
        TypeCheckReport r = check(mas, code);
        assertProblems(1, r); // temperatura foi sobrescrito; servir continua valendo
        assertHasProblem("servir(10)", r);

        r = check(mas, "{ type(temperatura(atom, string)) } temperatura(forno, 10).");
        assertHasProblem("sobrescreve a declaração do .mas2j", r);
    }

    public void testMas2jToStringKeepsTypes() throws Exception {
        String mas = "MAS m { agents: a; types { pos(number, number); caminho(list(pos)); } }";
        MAS2JProject p1 = new mas2j(new StringReader(mas)).mas();
        MAS2JProject p2 = new mas2j(new StringReader(p1.toString())).mas();
        assertEquals(p1.getTypeDecls(), p2.getTypeDecls());
    }

    public void testMas2jListType() throws Exception {
        String mas = "MAS m { agents: a; types { pos(number, number); caminho(list(pos)); } }";
        assertTrue(check(mas, "caminho([pos(1,2)]).").isOk());
        assertProblems(1, check(mas, "caminho([1])."));
    }

    // ---- execução normal --------------------------------------------------

    public void testDirectiveIsIgnoredWhenNotChecking() throws Exception {
        // decisão 3c: com a verificação desligada, a diretiva é aceita e ignorada
        Agent ag = new Agent();
        ag.setConsiderToAddMIForThisAgent(false);
        ag.initAg();
        ag.parseAS(new StringReader("{ type(temperatura(atom, number)) } temperatura(forno, \"duzentos\")."), "t.asl");
        assertEquals(1, ag.getInitialBels().size());
        assertEquals(ASSyntax.parseLiteral("temperatura(forno, \"duzentos\")"), ag.getInitialBels().get(0));
    }
}
