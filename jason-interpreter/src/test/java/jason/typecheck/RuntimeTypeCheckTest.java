package jason.typecheck;

import jason.asSyntax.ASSyntax;
import junit.framework.TestCase;

/** Testes da verificação de tipos em execução (conteúdo das mensagens). */
public class RuntimeTypeCheckTest extends TestCase {

    private TypeTable table;

    @Override
    protected void setUp() throws Exception {
        TypeCheckReport r = new TypeCheckReport();
        table = new TypeTable();
        for (String d : new String[] { "temperatura(atom, number)", "pos(number, number)", "localizacao(atom, pos)" })
            table.add(TypeDirective.parseDecl(ASSyntax.parseLiteral(d), "t.mas2j", 1, true, r), r);
        assertTrue(r.isOk());
    }

    private TypeCheckReport check(String content) throws Exception {
        return RuntimeTypeCheck.checkContent(table, ASSyntax.parseTerm(content), "mensagem tell de a");
    }

    public void testCorrectContent() throws Exception {
        assertTrue(check("temperatura(forno, 200)").isOk());
        assertTrue(check("temperatura(forno, 200)[source(a)]").isOk());
    }

    public void testWrongContent() throws Exception {
        TypeCheckReport r = check("temperatura(forno, \"alta\")");
        assertEquals(1, r.getProblems().size());
        assertEquals("mensagem tell de a", r.getProblems().get(0).getLocation());
        assertTrue(r.getProblems().get(0).getMessage().contains("esperado number, encontrado string"));
    }

    public void testListOfLiterals() throws Exception {
        // .send(b, tell, [temperatura(forno, 1), temperatura(fogao, x)])
        assertEquals(1, check("[temperatura(forno, 1), temperatura(fogao, x)]").getProblems().size());
    }

    public void testNestedStructure() throws Exception {
        assertTrue(check("localizacao(robo, pos(1, 2))").isOk());
        assertEquals(1, check("localizacao(robo, pos(1, a))").getProblems().size());
    }

    public void testVariablesAreAccepted() throws Exception {
        // ex.: conteúdo de um askOne: temperatura(forno, X)
        assertTrue(check("temperatura(forno, X)").isOk());
    }

    public void testUndeclaredIsNotChecked() throws Exception {
        assertTrue(check("outra(\"x\", 1)").isOk());
        assertTrue(check("temperatura(forno)").isOk()); // aridade não declarada (decisão 6a)
    }
}
