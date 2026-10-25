package jason.typecheck;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jason.asSyntax.PredicateIndicator;

/**
 * A "tabela de tipos": para cada predicado (nome/aridade), o seu tipo declarado.
 * Ex.: temperatura/2 -> temperatura(atom, number)
 *
 * Existe uma tabela com as declarações do .mas2j (globais) e uma por agente
 * (declarações do .asl e dos arquivos incluídos). A tabela usada para verificar
 * um agente é a junção das duas, feita por {@link #merge}.
 */
public class TypeTable {

    private final Map<PredicateIndicator, TypeDecl> decls = new LinkedHashMap<>();

    /** predicados cuja declaração do .asl substituiu a do .mas2j */
    private final Set<PredicateIndicator> overridden = new HashSet<>();

    /**
     * Adiciona uma declaração. Se o mesmo predicado já foi declarado nesta tabela
     * com outros tipos, registra um problema no relatório.
     */
    public void add(TypeDecl d, TypeCheckReport report) {
        PredicateIndicator pi = d.getPI();
        TypeDecl old = decls.get(pi);
        if (old == null) {
            decls.put(pi, d);
        } else if (!old.getArgTypes().equals(d.getArgTypes())) {
            report.addProblem(d.getLocation(), "{ type(" + d + ") }",
                    "declaração duplicada de " + pi + " com tipos diferentes; já declarado como " + old + " em " + old.getLocation(),
                    null);
        }
        // declaração repetida com os mesmos tipos: simplesmente ignora
    }

    /**
     * Junta as declarações globais (.mas2j) com as de um agente (.asl).
     * A declaração do agente substitui a global, predicado por predicado.
     */
    public static TypeTable merge(TypeTable global, TypeTable agent) {
        TypeTable t = new TypeTable();
        t.decls.putAll(global.decls);
        for (TypeDecl d : agent.decls.values()) {
            if (t.decls.containsKey(d.getPI()))
                t.overridden.add(d.getPI());
            t.decls.put(d.getPI(), d);
        }
        return t;
    }

    public TypeDecl get(String functor, int arity) {
        return decls.get(new PredicateIndicator(functor, arity));
    }

    public boolean isEmpty() {
        return decls.isEmpty();
    }

    public Collection<TypeDecl> getAll() {
        return decls.values();
    }

    /** true se existe alguma declaração com esse nome (qualquer aridade) */
    public boolean hasFunctor(String functor) {
        for (PredicateIndicator pi : decls.keySet())
            if (pi.getFunctor().equals(functor))
                return true;
        return false;
    }

    /** true se existe declaração para esse nome com essa aridade */
    public boolean has(String functor, int arity) {
        return decls.containsKey(new PredicateIndicator(functor, arity));
    }

    /** texto para a linha "tipo declarado em" das mensagens */
    public String describeOrigin(TypeDecl d) {
        String s = d.getLocation();
        if (overridden.contains(d.getPI()))
            s += " (sobrescreve a declaração do .mas2j)";
        return s;
    }

    /**
     * Confere se todos os nomes de tipo usados nas declarações existem.
     * Ex.: em localizacao(atom, pos), "pos" precisa estar declarado.
     */
    public void validate(TypeCheckReport report) {
        for (TypeDecl d : decls.values()) {
            for (Type t : d.getArgTypes()) {
                String unknown = unknownName(t);
                if (unknown != null) {
                    report.addProblem(d.getLocation(), "{ type(" + d + ") }",
                            "tipo desconhecido '" + unknown + "' (use atom, number, string, boolean, list, list(T), any ou o nome de um predicado declarado)",
                            null);
                }
            }
        }
    }

    private String unknownName(Type t) {
        if (t.isStruct() && !hasFunctor(t.getStructName()))
            return t.getStructName();
        if (t.isList() && t.getElemType() != null)
            return unknownName(t.getElemType());
        return null;
    }
}
