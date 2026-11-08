package jason.typecheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jason.JasonException;
import jason.asSemantics.Agent;
import jason.asSyntax.Literal;
import jason.asSyntax.SourceInfo;
import jason.mas2j.AgentParameters;
import jason.mas2j.MAS2JProject;
import jason.runtime.SourcePath;

/**
 * A etapa de verificação estática de tipos, chamada pelo RunLocalMAS antes de criar
 * o ambiente e os agentes (e pelo LocalRuntimeServices quando um agente é criado
 * com .create_agent durante a execução).
 *
 * Comportamento:
 *  - se não há nenhuma declaração de tipo (nem no .mas2j, nem nos .asl): não faz nada
 *    e não imprime nada (o Jason se comporta exatamente como antes);
 *  - se há declarações: verifica todos os agentes e imprime
 *    "Verificação estática de tipos [OK]" ou "[FAIL]" com a lista de problemas;
 *  - se der [FAIL], por padrão o sistema roda mesmo assim (os erros aparecem como aviso);
 *    com "jason.typecheck.onfail = stop" o sistema não é iniciado.
 * Além disso, registra as tabelas de tipos para a verificação em execução
 * ({@link RuntimeTypeCheck}).
 *
 * Opções no logging.properties do projeto:
 *     jason.typecheck = false              desliga toda a verificação de tipos (estática e em execução)
 *     jason.typecheck.static = false       desliga só a verificação estática
 *     jason.typecheck.onfail = warn        em caso de [FAIL], só avisa e roda o sistema mesmo assim (padrão)
 *     jason.typecheck.onfail = stop        em caso de [FAIL], não inicia o sistema
 *     jason.typecheck.report = tipos.log   também salva o relatório nesse arquivo
 * (as opções da verificação em execução estão em RuntimeTypeCheck)
 */
public class StaticTypeCheck {

    public static final String PROP_ENABLED = "jason.typecheck";
    public static final String PROP_STATIC  = "jason.typecheck.static";
    public static final String PROP_ONFAIL  = "jason.typecheck.onfail";
    public static final String PROP_REPORT  = "jason.typecheck.report";

    // o nome do logger aparece entre colchetes na saída: [TypeCheck] ...
    private static final Logger logger = Logger.getLogger("jason.typecheck.TypeCheck");

    // loggers silenciados durante o parse feito pelo verificador, para que avisos e erros
    // de sintaxe não apareçam duas vezes (o Jason faz o parse de novo ao criar os agentes)
    private static final String[] QUIET_LOGGERS = {
            "aslparser",
            "jason.asSyntax.directives.Include",
            "jason.asSyntax.directives.DirectiveProcessor"
    };

    private static final Pattern TYPE_DIRECTIVE = Pattern.compile("\\{\\s*" + TypeDirective.NAME + "\\s*\\(");
    private static final Pattern INCLUDE        = Pattern.compile("include\\s*\\(\\s*\"([^\"]+)\"");

    // caminhos dos fontes do projeto (usados para agentes criados durante a execução)
    private static SourcePath projectPaths = new SourcePath();

    private StaticTypeCheck() {}

    /**
     * Executa a verificação no início da execução.
     * @return true se o sistema pode ser iniciado; false se deve parar (houve [FAIL] e onfail = stop)
     */
    public static boolean check(MAS2JProject project) {
        try {
            return doCheck(project);
        } catch (RuntimeException e) {
            internalError(e);
            return true;
        }
    }

    private static boolean doCheck(MAS2JProject project) {
        RuntimeTypeCheck.clear();
        projectPaths = project.getSourcePaths();

        if (isOff(PROP_ENABLED))
            return true;
        boolean staticOn = !isOff(PROP_STATIC);
        if (!staticOn && !RuntimeTypeCheck.isEnabled())
            return true;

        // Antes de fazer o parse dos agentes, uma busca rápida no texto: se nenhum arquivo
        // tem declaração de tipo, não há nada a verificar. Isso evita qualquer efeito
        // colateral (parse duplicado) em projetos que não usam tipos.
        if (project.getTypeDecls().isEmpty() && !anyAgentMayHaveTypes(project))
            return true;

        TypeCheckReport report = new TypeCheckReport();
        Analysis a = analyse(project, report);
        if (!a.anyDecl)
            return true; // havia "type(" no texto, mas nenhuma declaração de verdade (ex.: em comentário)

        // as tabelas de tipos ficam guardadas para a verificação em execução (mensagens)
        RuntimeTypeCheck.setGlobal(a.global);
        a.merged.forEach(RuntimeTypeCheck::register);

        if (!staticOn)
            return true;

        printReport(report);
        if (!report.isOk()) {
            if (stopOnFail()) {
                logger.warning("O sistema não será iniciado por causa dos erros de tipo (" + PROP_ONFAIL + " = stop no logging.properties).");
                return false;
            }
            logger.warning("O sistema vai rodar mesmo assim. (Para não deixar rodar quando há erros de tipo, use '"
                    + PROP_ONFAIL + " = stop' no logging.properties.)");
        }
        return true;
    }

    /**
     * Verifica um agente criado durante a execução (.create_agent), antes de ele ser criado.
     * Se o .asl já foi verificado no início (outro agente usa o mesmo arquivo), não faz nada.
     * @throws JasonException se houver erros de tipo e onfail = stop: o agente não é
     *         criado e o .create_agent falha (o plano de quem chamou falha)
     */
    public static void checkCreatedAgent(String agName, String src) throws JasonException {
        try {
            doCheckCreatedAgent(agName, src);
        } catch (RuntimeException e) {
            internalError(e);
        }
    }

    private static void doCheckCreatedAgent(String agName, String src) throws JasonException {
        if (src == null || isOff(PROP_ENABLED) || RuntimeTypeCheck.isRegistered(src))
            return;
        boolean staticOn = !isOff(PROP_STATIC);
        if (!staticOn && !RuntimeTypeCheck.isEnabled())
            return;
        TypeTable global = RuntimeTypeCheck.getGlobal();
        if (global.isEmpty() && !mayHaveTypes(src, projectPaths, new HashSet<>()))
            return;

        TypeCheckReport report = new TypeCheckReport();
        report.setSubject("agente " + agName + ", criado durante a execução");
        Parsed p = parseAgent(ag -> ag.loadAS(src), report);
        if (p.agent == null)
            return; // erro de sintaxe: o próprio Jason mostra ao criar o agente
        TypeTable merged = checkParsed(p, global, report);
        if (merged.isEmpty() && report.isOk())
            return;
        RuntimeTypeCheck.register(src, merged);

        if (!staticOn)
            return;
        printReport(report);
        if (!report.isOk()) {
            if (stopOnFail())
                throw new JasonException("o agente " + agName + " não foi criado por causa dos erros de tipo");
            logger.warning("O agente " + agName + " vai ser criado mesmo assim.");
        }
    }

    /**
     * Faz a verificação e retorna o relatório, sem imprimir nada.
     * Retorna null se não existe nenhuma declaração de tipo.
     * (Separado do método acima para poder ser usado pelos testes.)
     */
    public static TypeCheckReport check(MAS2JProject project, TypeCheckReport report) {
        return analyse(project, report).anyDecl ? report : null;
    }

    /** resultado da análise do projeto: a tabela global, a tabela de cada .asl e se havia declarações */
    private record Analysis(TypeTable global, Map<String, TypeTable> merged, boolean anyDecl) {}

    private static Analysis analyse(MAS2JProject project, TypeCheckReport report) {
        // 1. declarações globais (bloco types do .mas2j)
        TypeTable global = globalTable(project, report);

        // 2. parse de cada agente, coletando as declarações do .asl (e dos arquivos incluídos)
        Map<String, Parsed> agents = new LinkedHashMap<>();
        boolean anyDecl = !global.isEmpty() || !report.isOk();
        for (AgentParameters ap : project.getAgents()) {
            if (ap.getSource() == null)
                continue;
            String src = ap.getSource().toString();
            if (agents.containsKey(src))
                continue; // o mesmo .asl usado por vários agentes é verificado uma vez só

            Parsed p = parseAgent(ag -> ag.loadAS(src), report);
            if (p.agent == null)
                continue; // erro de sintaxe: o próprio Jason vai mostrar o erro ao criar o agente
            anyDecl = anyDecl || !p.decls.isEmpty() || !report.isOk();
            agents.put(src, p);
        }

        Map<String, TypeTable> merged = new LinkedHashMap<>();
        if (!anyDecl)
            return new Analysis(global, merged, false);

        // 3. verifica cada agente com a junção: .mas2j + sobrescritas do .asl
        if (agents.isEmpty())
            global.validate(report);
        for (Map.Entry<String, Parsed> e : agents.entrySet())
            merged.put(e.getKey(), checkParsed(e.getValue(), global, report));
        return new Analysis(global, merged, true);
    }

    private static void printReport(TypeCheckReport report) {
        if (report.isOk())
            logger.info(report.format());
        else
            logger.warning(report.format());

        String reportFile = property(PROP_REPORT);
        if (reportFile != null && !reportFile.isBlank()) {
            try {
                report.writeToFile(reportFile.trim());
            } catch (IOException e) {
                logger.warning("não foi possível salvar o relatório em " + reportFile + ": " + e.getMessage());
            }
        }
    }

    /**
     * Um erro dentro do próprio verificador nunca pode impedir o Jason de funcionar:
     * avisa e segue sem a verificação.
     */
    static void internalError(RuntimeException e) {
        logger.log(Level.WARNING, "erro interno no verificador de tipos; a verificação foi ignorada neste ponto", e);
    }

    private static boolean stopOnFail() {
        String v = property(PROP_ONFAIL);
        return v != null && "stop".equalsIgnoreCase(v.trim()); // padrão: warn (avisa e roda)
    }

    private static boolean isOff(String prop) {
        return "false".equalsIgnoreCase(property(prop));
    }

    /**
     * Verifica um trecho de código AgentSpeak escrito numa string (usado pelos testes).
     * @param project projeto com as declarações globais (bloco types), ou null
     * @throws IllegalArgumentException se o código tiver erro de sintaxe
     */
    public static TypeCheckReport checkCode(String aslCode, MAS2JProject project) {
        TypeCheckReport report = new TypeCheckReport();
        TypeTable global = project == null ? new TypeTable() : globalTable(project, report);
        Parsed p = parseAgent(ag -> ag.parseAS(new StringReader(aslCode), "teste.asl"), report);
        if (p.agent == null)
            throw new IllegalArgumentException("erro de sintaxe no código de teste", p.error);
        checkParsed(p, global, report);
        return report;
    }

    private static TypeTable globalTable(MAS2JProject project, TypeCheckReport report) {
        TypeTable global = new TypeTable();
        String projectFile = project.getProjectFile() != null ? project.getProjectFile().getName() : "mas2j";
        for (Literal l : project.getTypeDecls()) {
            SourceInfo si = l.getSrcInfo();
            TypeDecl d = TypeDirective.parseDecl(l, projectFile, si != null ? si.getBeginSrcLine() : 0, true, report);
            if (d != null)
                global.add(d, report);
        }
        return global;
    }

    /** verifica o agente e retorna a tabela usada (.mas2j + sobrescritas do .asl) */
    private static TypeTable checkParsed(Parsed p, TypeTable global, TypeCheckReport report) {
        TypeTable agentTable = new TypeTable();
        for (TypeDecl d : p.decls)
            agentTable.add(d, report);
        TypeTable merged = TypeTable.merge(global, agentTable);
        merged.validate(report);
        new TypeChecker(merged, report).checkAgent(p.agent);
        return merged;
    }

    /** resultado do parse de um agente: o código, as declarações de tipo encontradas e o erro (se houve) */
    private record Parsed(Agent agent, List<TypeDecl> decls, Exception error) {}

    /** como carregar o código no agente (de um arquivo ou de uma string) */
    private interface Loader {
        void load(Agent ag) throws Exception;
    }

    /** faz o parse de um .asl num agente "de mentira", só para olhar o código */
    private static Parsed parseAgent(Loader loader, TypeCheckReport report) {
        Map<Logger, Level> oldLevels = new LinkedHashMap<>();
        for (String name : QUIET_LOGGERS) {
            Logger l = Logger.getLogger(name);
            oldLevels.put(l, l.getLevel());
            l.setLevel(Level.OFF);
        }
        TypeDirective.startCollecting(report);
        Agent ag = new Agent();
        Exception error = null;
        try {
            ag.setConsiderToAddMIForThisAgent(false); // não aparecer no mind inspector
            ag.initAg();
            loader.load(ag);
        } catch (Exception e) {
            ag = null;
            error = e;
        } finally {
            for (Map.Entry<Logger, Level> e : oldLevels.entrySet())
                e.getKey().setLevel(e.getValue());
        }
        return new Parsed(ag, new ArrayList<>(TypeDirective.stopCollecting()), error);
    }

    // ------------------------------------------------------------------
    // Busca rápida no texto dos arquivos
    // ------------------------------------------------------------------

    private static boolean anyAgentMayHaveTypes(MAS2JProject project) {
        Set<String> visited = new HashSet<>();
        for (AgentParameters ap : project.getAgents()) {
            if (ap.getSource() != null && mayHaveTypes(ap.getSource().toString(), project.getSourcePaths(), visited))
                return true;
        }
        return false;
    }

    /** true se o arquivo (ou algum arquivo incluído por ele) parece ter "{ type(" */
    private static boolean mayHaveTypes(String src, SourcePath projectPaths, Set<String> visited) {
        if (!visited.add(src))
            return false;
        String text;
        try {
            text = readText(src);
        } catch (Exception e) {
            return false; // não deu para ler: o próprio Jason também não vai conseguir incluir esse arquivo
        }
        if (TYPE_DIRECTIVE.matcher(text).find())
            return true;
        Matcher m = INCLUDE.matcher(text);
        while (m.find()) {
            SourcePath sp = new SourcePath();
            sp.addParentInPath(src);
            sp.addAll(projectPaths);
            if (mayHaveTypes(sp.fixPath(m.group(1)), projectPaths, visited))
                return true;
        }
        return false;
    }

    private static String readText(String src) throws IOException {
        InputStream in;
        if (src.startsWith(SourcePath.CRPrefix)) {
            in = Agent.class.getResource(src.substring(SourcePath.CRPrefix.length())).openStream();
        } else {
            try {
                in = new URL(src).openStream();
            } catch (MalformedURLException e) {
                return Files.readString(Paths.get(src), StandardCharsets.UTF_8);
            }
        }
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String property(String name) {
        return LogManager.getLogManager().getProperty(name);
    }
}
