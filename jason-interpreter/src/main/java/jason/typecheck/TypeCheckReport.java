package jason.typecheck;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Junta os problemas encontrados pelo verificador e monta o texto do relatório:
 *
 *   Verificação estática de tipos [OK]
 * ou
 *   Verificação estática de tipos [FAIL] - 2 problemas
 *
 *   supervisor.asl:12  temperatura(forno,"duzentos")
 *       argumento 2: esperado number, encontrado string
 *       tipo declarado em: cozinha.mas2j:4
 */
public class TypeCheckReport {

    public static final String TITLE = "Verificação estática de tipos";

    /** um problema encontrado */
    public static class Problem {
        private final String location; // arquivo:linha
        private final String code;     // trecho do código
        private final String message;  // o que está errado
        private final String origin;   // onde o tipo foi declarado (pode ser null)

        Problem(String location, String code, String message, String origin) {
            this.location = location;
            this.code     = code;
            this.message  = message;
            this.origin   = origin;
        }

        public String getLocation() { return location; }
        public String getCode()     { return code; }
        public String getMessage()  { return message; }
        public String getOrigin()   { return origin; }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            s.append(location).append("  ").append(code).append("\n");
            s.append("    ").append(message).append("\n");
            if (origin != null)
                s.append("    tipo declarado em: ").append(origin).append("\n");
            return s.toString();
        }
    }

    private final List<Problem> problems = new ArrayList<>();
    private String subject = null; // ex.: "agente bob, criado durante a execução"
    private final Set<String>   seen     = new LinkedHashSet<>(); // evita repetir o mesmo problema

    public void addProblem(String location, String code, String message, String origin) {
        Problem p = new Problem(location, code, message, origin);
        if (seen.add(p.toString()))
            problems.add(p);
    }

    /** sobre o que é o relatório (aparece no título); null = o sistema inteiro */
    public void setSubject(String subject) {
        this.subject = subject;
    }

    public boolean isOk() {
        return problems.isEmpty();
    }

    public List<Problem> getProblems() {
        return Collections.unmodifiableList(problems);
    }

    /** primeira linha: [OK] ou [FAIL] */
    public String getSummary() {
        String title = subject == null ? TITLE : TITLE + " (" + subject + ")";
        if (isOk())
            return title + " [OK]";
        int n = problems.size();
        return title + " [FAIL] - " + n + (n == 1 ? " problema" : " problemas");
    }

    /** relatório completo */
    public String format() {
        StringBuilder s = new StringBuilder(getSummary()).append("\n");
        for (Problem p : problems)
            s.append("\n").append(p);
        return s.toString();
    }

    public void writeToFile(String fileName) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(fileName), StandardCharsets.UTF_8))) {
            out.print(format());
        }
    }

    /** "file:/home/x/proj/bob.asl" -> "bob.asl" */
    static String shortFile(String src) {
        if (src == null)
            return "?";
        String s = src.replace('\\', '/');
        int i = s.lastIndexOf('/');
        if (i >= 0)
            s = s.substring(i + 1);
        if (s.startsWith("file:"))
            s = s.substring(5);
        return s;
    }
}
