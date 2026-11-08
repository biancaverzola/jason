package jason.typecheck;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jason.asSemantics.Agent;
import jason.asSyntax.ArithExpr;
import jason.asSyntax.ArithFunctionTerm;
import jason.asSyntax.InternalActionLiteral;
import jason.asSyntax.ListTerm;
import jason.asSyntax.Literal;
import jason.asSyntax.LogExpr;
import jason.asSyntax.LogicalFormula;
import jason.asSyntax.Plan;
import jason.asSyntax.PlanBody;
import jason.asSyntax.RelExpr;
import jason.asSyntax.Rule;
import jason.asSyntax.SourceInfo;
import jason.asSyntax.Term;
import jason.asSyntax.VarTerm;

/**
 * O verificador de tipos: percorre o código de um agente (já transformado em objetos
 * pelo parser) e confere cada uso de predicado declarado contra a tabela de tipos.
 *
 * O que é verificado:
 *  - crenças iniciais e regras
 *  - objetivos iniciais
 *  - planos: gatilho, contexto e corpo (ações, +crença, -crença, !objetivo, ?consulta,
 *    e o conteúdo de if/while/for)
 *
 * Inferência de variáveis (nível 2): o tipo de uma variável é deduzido
 *  - da posição em que ela aparece num predicado declarado, e
 *  - de operações aritméticas (+, -, *, /, ...) e comparações com números (>, <, ...)
 * A primeira informação define o tipo; as seguintes precisam ser compatíveis com ela.
 * Cada plano (e cada regra) tem as suas próprias variáveis.
 *
 * O que NÃO é verificado (por enquanto):
 *  - argumentos de ações internas (.print, .send, .length, ...), exceto if/while/for
 *  - anotações ([source(...)])
 *  - percepções vindas do ambiente (só existem durante a execução)
 */
public class TypeChecker {

    /** ações internas que, na verdade, são estruturas de controle com planos dentro */
    private static final Set<String> CONTROL_STRUCTURES = Set.of(".if_then_else", ".foreach", ".loop", ".fork");

    private final TypeTable       table;
    private final TypeCheckReport report;

    public TypeChecker(TypeTable table, TypeCheckReport report) {
        this.table  = table;
        this.report = report;
    }

    /** Onde estamos no código: arquivo, linha e o trecho que será mostrado na mensagem. */
    private static class Ctx {
        final String file;
        final int    line;
        final String code;
        Ctx(String file, int line, String code) {
            this.file = file;
            this.line = line;
            this.code = code;
        }
        String location() {
            return TypeCheckReport.shortFile(file) + (line > 0 ? ":" + line : "");
        }
    }

    /** O que já se sabe sobre uma variável: o tipo e de onde ele veio. */
    private static class VarInfo {
        Type   type;
        String origin; // ex.: "temperatura/2, linha 18"
        // a lista escrita no código que a variável recebeu (ex.: L = [1, "a"]), guardada para
        // conferir os elementos quando a variável for usada onde se espera list(T)
        ListTerm list;
        String   listOrigin;
        VarInfo(Type type, String origin) {
            this.type   = type;
            this.origin = origin;
        }
    }

    /** As variáveis de um plano ou regra. */
    private static class Scope {
        final Map<String, VarInfo> vars = new HashMap<>();
    }

    // ------------------------------------------------------------------
    // Ponto de entrada
    // ------------------------------------------------------------------

    public void checkAgent(Agent ag) {
        for (Literal b : ag.getInitialBels()) {
            Ctx c = ctxFor(b, null);
            Scope s = new Scope();
            if (b.isRule()) {
                Rule r = (Rule) b;
                checkLiteral(r.getHead(), s, c);
                checkFormula(r.getBody(), s, c);
            } else {
                checkLiteral(b, s, c);
            }
        }

        for (Literal g : ag.getInitialGoals()) {
            checkLiteral(g, new Scope(), ctxFor(g, null));
        }

        for (Plan p : ag.getPL()) {
            checkPlan(p);
        }
    }

    /**
     * Confere um literal isolado, por exemplo o conteúdo de uma mensagem recebida
     * durante a execução (ver {@link RuntimeTypeCheck}).
     * @param where descrição de onde veio, para a mensagem (ex.: "mensagem tell de a")
     */
    public void checkContent(Literal l, String where) {
        Ctx c = new Ctx(where, 0, show(l));
        Scope s = new Scope();
        TypeDecl d = table.get(l.getFunctor(), l.getArity());
        for (int i = 0; i < l.getArity(); i++) {
            Type expected = d != null ? d.getArgType(i) : Type.ANY;
            checkTerm(l.getTerm(i), expected, s, c, d, "argumento " + (i + 1));
        }
    }

    private void checkPlan(Plan p) {
        Ctx c = ctxFor(p, null);
        Scope s = new Scope(); // cada plano tem as suas variáveis
        checkLiteral(p.getTrigger().getLiteral(), s, c);
        checkFormula(p.getContext(), s, c);
        checkBody(p.getBody(), s, c);
    }

    // ------------------------------------------------------------------
    // Corpo do plano
    // ------------------------------------------------------------------

    private void checkBody(PlanBody body, Scope s, Ctx parent) {
        for (PlanBody b = body; b != null && !b.isEmptyBody(); b = b.getBodyNext()) {
            Term t = b.getBodyTerm();
            if (t == null || t.isVar())
                continue; // ex.: !G, com G variável: não dá para saber o que é
            // todos os tipos de passo do corpo são fórmulas: ações, !objetivo, ?consulta,
            // +crença, -crença, ações internas (.print, if, while...) e restrições (X = T + 1)
            if (b.getBodyType() != PlanBody.BodyType.none && t instanceof LogicalFormula)
                checkFormula((LogicalFormula) t, s, ctxFor(t, parent));
        }
    }

    // ------------------------------------------------------------------
    // Fórmulas (contexto, corpo de regras, condições de if/while)
    // ------------------------------------------------------------------

    private void checkFormula(LogicalFormula f, Scope s, Ctx parent) {
        if (f == null)
            return;
        if (f instanceof LogExpr) {        // &, |, not
            LogExpr le = (LogExpr) f;
            checkFormula(le.getLHS(), s, parent);
            if (!le.isUnary())             // "not" só tem um lado
                checkFormula(le.getRHS(), s, parent);
        } else if (f instanceof RelExpr) { // >, <, ==, =, ...
            checkRelExpr((RelExpr) f, s, ctxFor(f, parent));
        } else if (f instanceof Literal) {
            checkLiteral((Literal) f, s, ctxFor(f, parent));
        }
    }

    // ------------------------------------------------------------------
    // Literais: crenças, objetivos, ações, gatilhos
    // ------------------------------------------------------------------

    private void checkLiteral(Literal l, Scope s, Ctx parent) {
        if (l == null || l.isVar())
            return;
        if (l instanceof InternalActionLiteral) {
            checkInternalAction((InternalActionLiteral) l, s, parent);
            return;
        }
        Ctx c = ctxFor(l, parent);
        TypeDecl d = table.get(l.getFunctor(), l.getArity());
        for (int i = 0; i < l.getArity(); i++) {
            Type expected = d != null ? d.getArgType(i) : Type.ANY;
            checkTerm(l.getTerm(i), expected, s, c, d, "argumento " + (i + 1));
        }
    }

    private void checkInternalAction(InternalActionLiteral ia, Scope s, Ctx c) {
        if (!CONTROL_STRUCTURES.contains(ia.getFunctor()))
            return; // .print, .send, ...: argumentos não são verificados (nível 3)
        for (Term arg : ia.getTerms()) {
            if (arg.isPlanBody())
                checkBody((PlanBody) arg, s, c);
            else if (arg instanceof LogicalFormula)
                checkFormula((LogicalFormula) arg, s, c);
        }
    }

    // ------------------------------------------------------------------
    // Termos: os argumentos dos literais
    // ------------------------------------------------------------------

    /**
     * Confere um termo contra o tipo esperado.
     * @param d   a declaração do predicado onde o termo aparece (ou null)
     * @param pos descrição da posição, para a mensagem (ex.: "argumento 2")
     */
    private void checkTerm(Term t, Type expected, Scope s, Ctx c, TypeDecl d, String pos) {
        if (t.isUnnamedVar())
            return;

        if (t.isVar()) {
            if (!expected.isAny())
                constrainVar((VarTerm) t, expected, s, c, pos, originOf(d, pos, c), d);
            return;
        }

        if (t instanceof ArithFunctionTerm) {          // ex.: math.abs(X)
            expect(expected, Type.NUMBER, t, c, d, pos);
            return;
        }
        if (t.isArithExpr()) {                         // ex.: T + 1
            checkArith((ArithExpr) t, s, c);
            expect(expected, Type.NUMBER, t, c, d, pos);
            return;
        }
        if (t.isNumeric()) {
            expect(expected, Type.NUMBER, t, c, d, pos);
            return;
        }
        if (t.isString()) {
            expect(expected, Type.STRING, t, c, d, pos);
            return;
        }
        if (t.isList()) {
            checkList((ListTerm) t, expected, s, c, d, pos);
            return;
        }
        if (t.isLiteral()) {
            Literal l = (Literal) t;
            if (l.getArity() == 0) {
                checkAtom(l, expected, c, d, pos);
            } else {
                checkLiteral(l, s, c);  // a estrutura também pode ter tipo declarado (ex.: pos(3,5))
                checkStructure(l, expected, c, d, pos);
            }
        }
        // outros termos (mapas, conjuntos, objetos Java): não verificados
    }

    private void checkAtom(Literal atom, Type expected, Ctx c, TypeDecl d, String pos) {
        if (expected.isStruct() && atom.getFunctor().equals(expected.getStructName()) && table.has(atom.getFunctor(), 0))
            return; // ex.: tipo "vazio" declarado como vazio
        expect(expected, isBoolean(atom) ? Type.BOOLEAN : Type.ATOM, atom, c, d, pos);
    }

    private void checkStructure(Literal l, Type expected, Ctx c, TypeDecl d, String pos) {
        if (expected.isAny())
            return;
        if (expected.isStruct() && l.getFunctor().equals(expected.getStructName())) {
            if (!table.has(l.getFunctor(), l.getArity()))
                problem(c, pos + ": esperado " + expected + ", mas " + l.getFunctor() + "/" + l.getArity()
                        + " não foi declarado", d);
            return;
        }
        problem(c, pos + ": esperado " + expected + ", encontrado a estrutura " + l, d);
    }

    private void checkList(ListTerm lt, Type expected, Scope s, Ctx c, TypeDecl d, String pos) {
        if (!expected.isAny() && !expected.isList()) {
            problem(c, pos + ": esperado " + expected + ", encontrado list " + lt, d);
            return;
        }
        Type elemType = expected.isList() && expected.getElemType() != null ? expected.getElemType() : Type.ANY;
        int i = 1;
        for (Term e : lt.getAsList()) {
            checkTerm(e, elemType, s, c, d, pos + ", elemento " + i);
            i++;
        }
        VarTerm tail = lt.getTail(); // [H|T]: T também é uma lista do mesmo tipo
        if (tail != null && !tail.isUnnamedVar() && expected.isList())
            constrainVar(tail, expected, s, c, pos + ", resto da lista", originOf(d, pos, c), d);
    }

    // ------------------------------------------------------------------
    // Operações com números (nível 2)
    // ------------------------------------------------------------------

    private void checkArith(ArithExpr e, Scope s, Ctx c) {
        checkNumericOperand(e.getLHS(), "operação " + show(e), s, c);
        if (!e.isUnary())                  // ex.: -X só tem um lado
            checkNumericOperand(e.getRHS(), "operação " + show(e), s, c);
    }

    /** o termo precisa ser um número (operando de +, -, >, <, ...) */
    private void checkNumericOperand(Term t, String where, Scope s, Ctx c) {
        if (t == null || t.isUnnamedVar())
            return;
        if (t.isVar()) {
            constrainVar((VarTerm) t, Type.NUMBER, s, c, where, where + ", linha " + c.line, null);
        } else if (t instanceof ArithFunctionTerm) {
            // função aritmética: resultado é número
        } else if (t.isArithExpr()) {
            checkArith((ArithExpr) t, s, c);
        } else if (!t.isNumeric()) {
            problem(c, where + ": esperado number, encontrado " + Type.describeValue(t), null);
        }
    }

    private void checkRelExpr(RelExpr r, Scope s, Ctx c) {
        Term lhs = r.getLHS();
        Term rhs = r.getRHS();
        String where = (r.getOp() == RelExpr.RelationalOp.unify ? "unificação " : "comparação ") + show(r);

        // estruturas e expressões dentro da comparação também são verificadas
        checkInside(lhs, s, c);
        checkInside(rhs, s, c);

        switch (r.getOp()) {
            case gt: case gte: case lt: case lte: {
                // em Jason, > e < também comparam átomos e strings; então só deduzimos
                // "é número" quando um dos lados já é sabidamente um número
                if (typeOf(lhs, s).getKind() == Type.Kind.NUMBER || typeOf(rhs, s).getKind() == Type.Kind.NUMBER) {
                    checkNumericOperand(lhs, where, s, c);
                    checkNumericOperand(rhs, where, s, c);
                }
                break;
            }
            case eq: case unify: { // == e =  : os dois lados têm o mesmo tipo
                if (isNamedVar(lhs) && rhs.isList()) {         // ex.: L = [1, "a"]
                    bindList((VarTerm) lhs, (ListTerm) rhs, s, c, where);
                    break;
                }
                if (isNamedVar(rhs) && lhs.isList()) {         // ex.: [1, "a"] = L
                    bindList((VarTerm) rhs, (ListTerm) lhs, s, c, where);
                    break;
                }
                Type tl = typeOf(lhs, s);
                Type tr = typeOf(rhs, s);
                if (lhs.isVar() && !lhs.isUnnamedVar() && !tr.isAny()) {
                    constrainVar((VarTerm) lhs, tr, s, c, where, where + ", linha " + c.line, null);
                } else if (rhs.isVar() && !rhs.isUnnamedVar() && !tl.isAny()) {
                    constrainVar((VarTerm) rhs, tl, s, c, where, where + ", linha " + c.line, null);
                } else if (!Type.compatible(tl, tr)) {
                    problem(c, where + ": os dois lados têm tipos incompatíveis (" + tl + " e " + tr + ")", null);
                }
                break;
            }
            default:
                break; // \== e =.. : nada a deduzir
        }
    }

    private static boolean isNamedVar(Term t) {
        return t != null && t.isVar() && !t.isUnnamedVar();
    }

    /**
     * A variável v recebe a lista lt escrita no código (ex.: L = [1, "a"]).
     * Se v já é list(T), os elementos são conferidos agora. Senão, v passa a ser uma
     * lista e a lista é guardada, para conferir os elementos quando v for usada onde
     * se espera list(T) (ver constrainVar).
     */
    private void bindList(VarTerm v, ListTerm lt, Scope s, Ctx c, String where) {
        String name = v.getFunctor();
        VarInfo info = s.vars.get(name);
        if (info != null && info.type.isList() && info.type.getElemType() != null) {
            checkList(lt, info.type, s, c, null, where + " (" + name + " vem de " + info.origin + ")");
            return;
        }
        String origin = where + ", linha " + c.line;
        constrainVar(v, Type.LIST, s, c, where, origin, null);
        info = s.vars.get(name);
        if (info.type.isList() && info.type.getElemType() == null) {
            info.list       = lt;
            info.listOrigin = origin;
        }
    }

    /** verifica o que existe dentro de um lado de comparação (expressões, estruturas) */
    private void checkInside(Term t, Scope s, Ctx c) {
        if (t == null || t.isVar())
            return;
        if (t.isArithExpr())
            checkArith((ArithExpr) t, s, c);
        else if (t.isList() || (t.isLiteral() && !(t instanceof ArithFunctionTerm)))
            checkTerm(t, Type.ANY, s, c, null, "termo");
    }

    // ------------------------------------------------------------------
    // Variáveis
    // ------------------------------------------------------------------

    /**
     * Registra que a variável v deve ter o tipo t.
     * Se ela ainda não tem tipo, passa a ter. Se já tem e é incompatível, é um erro.
     *
     * @param origin    de onde vem essa informação, para mensagens futuras (ex.: "temperatura/2, linha 18")
     * @param d         a declaração usada aqui (ou null)
     */
    private void constrainVar(VarTerm v, Type t, Scope s, Ctx c, String pos, String origin, TypeDecl d) {
        String declOrigin = d == null ? null : table.describeOrigin(d);
        String name = v.getFunctor();
        VarInfo info = s.vars.get(name);
        if (info == null) {
            s.vars.put(name, new VarInfo(t, origin));
        } else if (Type.compatible(info.type, t)) {
            if (info.list != null && t.isList() && t.getElemType() != null) {
                // ex.: L = [1, "a"]; +notas(L): confere os elementos da lista que L recebeu
                ListTerm lt = info.list;
                info.list = null;
                checkList(lt, t, s, c, d, pos + " (" + name + " vem de " + info.listOrigin + ")");
            }
            if (info.type.isAny())
                info.origin = origin;
            info.type = Type.moreSpecific(info.type, t);
        } else {
            report.addProblem(c.location(), c.code,
                    pos + ": esperado " + t + ", encontrado " + info.type
                            + " (" + name + " vem de " + info.origin + ")",
                    declOrigin);
        }
    }

    /** o tipo de um termo, sem registrar nada (usado nas comparações) */
    private Type typeOf(Term t, Scope s) {
        if (t == null || t.isUnnamedVar())
            return Type.ANY;
        if (t.isVar()) {
            VarInfo info = s.vars.get(((VarTerm) t).getFunctor());
            return info == null ? Type.ANY : info.type;
        }
        if (t instanceof ArithFunctionTerm || t.isArithExpr() || t.isNumeric())
            return Type.NUMBER;
        if (t.isString())
            return Type.STRING;
        if (t.isList())
            return Type.LIST;
        if (t.isLiteral()) {
            Literal l = (Literal) t;
            if (l.getArity() == 0)
                return isBoolean(l) ? Type.BOOLEAN : Type.ATOM;
            if (table.hasFunctor(l.getFunctor()))
                return Type.struct(l.getFunctor());
        }
        return Type.ANY;
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    /** registra um problema se 'actual' não serve onde se espera 'expected' */
    private void expect(Type expected, Type actual, Term value, Ctx c, TypeDecl d, String pos) {
        if (!Type.accepts(expected, actual))
            problem(c, pos + ": esperado " + expected + ", encontrado " + actual + " (" + value + ")", d);
    }

    private void problem(Ctx c, String message, TypeDecl d) {
        report.addProblem(c.location(), c.code, message, d == null ? null : table.describeOrigin(d));
    }

    /** de onde vem o tipo de uma variável, ex.: "temperatura/2, linha 18" */
    private static String originOf(TypeDecl d, String pos, Ctx c) {
        return (d != null ? d.getPI().toString() : pos) + ", linha " + c.line;
    }

    private static boolean isBoolean(Literal l) {
        return l.getArity() == 0 && (l.getFunctor().equals("true") || l.getFunctor().equals("false"));
    }

    /** texto de um termo para as mensagens, sem os parênteses externos: "(T > 180)" -> "T > 180" */
    private static String show(Term t) {
        String s = t.toString();
        if (s.startsWith("(") && s.endsWith(")"))
            s = s.substring(1, s.length() - 1);
        return s;
    }

    /** usa a linha do próprio termo, se o parser guardou; senão, a do elemento que o contém */
    private static Ctx ctxFor(Term t, Ctx parent) {
        SourceInfo si = t.getSrcInfo();
        if (si != null && si.getBeginSrcLine() > 0)
            return new Ctx(si.getSrcFile(), si.getBeginSrcLine(), show(t));
        if (parent != null)
            return new Ctx(parent.file, parent.line, show(t));
        return new Ctx(si != null ? si.getSrcFile() : null, 0, show(t));
    }
}
