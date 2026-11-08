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

    /** tipos enumerados, ex.: cor -> cor = [amarela, azul] */
    private final Map<String, TypeDecl> enums = new LinkedHashMap<>();

    /** tipos enumerados cuja declaração do .asl substituiu a do .mas2j */
    private final Set<String> overriddenEnums = new HashSet<>();

    /**
     * Adiciona uma declaração. Se o mesmo predicado já foi declarado nesta tabela
     * com outros tipos, registra um problema no relatório.
     */
    public void add(TypeDecl d, TypeCheckReport report) {
        if (d.isEnum()) {
            TypeDecl old = enums.get(d.getFunctor());
            if (old == null)
                enums.put(d.getFunctor(), d);
            else if (!old.getEnumValues().equals(d.getEnumValues()))
                report.addProblem(d.getLocation(), "{ type(" + d + ") }",
                        "declaração duplicada do tipo enumerado " + d.getFunctor() + " com valores diferentes; já declarado como "
                                + old + " em " + old.getLocation(), null);
            return;
        }
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
        t.enums.putAll(global.enums);
        for (TypeDecl e : agent.enums.values()) {
            if (t.enums.containsKey(e.getFunctor()))
                t.overriddenEnums.add(e.getFunctor());
            t.enums.put(e.getFunctor(), e);
        }
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
        return decls.isEmpty() && enums.isEmpty();
    }

    /**
     * Troca os nomes de tipos enumerados pelo tipo completo (com os valores aceitos).
     * Ex.: em mochila(cor), o tipo "cor" é lido como um nome; aqui vira cor = [amarela, azul].
     */
    public Type resolve(Type t) {
        if (t.isStruct() && enums.containsKey(t.getStructName()))
            return enums.get(t.getStructName()).getEnumType();
        if (t.isList() && t.getElemType() != null) {
            Type e = resolve(t.getElemType());
            return e == t.getElemType() ? t : Type.listOf(e);
        }
        return t;
    }

    public Collection<TypeDecl> getAll() {
        return decls.values();
    }

    /** as declarações de tipos enumerados */
    public Collection<TypeDecl> getEnums() {
        return enums.values();
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
        for (TypeDecl e : enums.values()) {
            if (hasFunctor(e.getFunctor()))
                report.addProblem(e.getLocation(), "{ type(" + e + ") }",
                        "o nome '" + e.getFunctor() + "' foi declarado como tipo enumerado e como predicado; use nomes diferentes",
                        null);
        }
        for (TypeDecl d : decls.values()) {
            for (Type t : d.getArgTypes()) {
                String unknown = unknownName(t);
                if (unknown != null) {
                    report.addProblem(d.getLocation(), "{ type(" + d + ") }",
                            "tipo desconhecido '" + unknown + "' (use atom, number, string, boolean, list, list(T), any, o nome de um predicado declarado ou de um tipo enumerado)",
                            null);
                }
            }
        }
    }

    private String unknownName(Type t) {
        if (t.isStruct() && !hasFunctor(t.getStructName()) && !enums.containsKey(t.getStructName()))
            return t.getStructName();
        if (t.isList() && t.getElemType() != null)
            return unknownName(t.getElemType());
        return null;
    }
}
