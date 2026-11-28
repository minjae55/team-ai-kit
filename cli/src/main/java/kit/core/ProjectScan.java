package kit.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프로젝트 폴더를 훑어 AGENTS.md 초안에 넣을 값을 찾는다 (kit init).
 * 비밀값이 있을 수 있는 파일(.env, *local*, *secret*, .my.cnf)은 열지 않고, docker-compose에서도 비밀번호 줄은 읽지 않는다.
 */
public final class ProjectScan {

    public static final class Service {
        public String name;
        public String image = "";
        public String container = "";
        public final List<String> ports = new ArrayList<String>();   // "호스트:컨테이너"
        public String database = "";

        public String hostPort(String containerPort) {
            for (String p : ports) {
                String[] a = p.split(":");
                if (a.length >= 2 && a[a.length - 1].equals(containerPort)) {
                    return a[a.length - 2];
                }
            }
            return ports.isEmpty() ? "" : ports.get(0).split(":")[0];
        }

        public boolean is(String... words) {
            String s = (image + " " + name).toLowerCase(Locale.ROOT);
            for (String w : words) {
                if (s.contains(w)) {
                    return true;
                }
            }
            return false;
        }
    }

    public String name;
    public String framework = "";
    public String appPort = "";
    public final List<Service> services = new ArrayList<Service>();
    public final List<String> composeFiles = new ArrayList<String>();
    public boolean hasK6;
    public boolean hasLogs;
    public boolean hasEvidence;

    public static ProjectScan scan(Path dir) throws IOException {
        ProjectScan s = new ProjectScan();
        s.name = dir.toAbsolutePath().normalize().getFileName().toString();
        Path backend = Files.isDirectory(dir.resolve("backend")) ? dir.resolve("backend") : dir;
        String build = read(backend.resolve("build.gradle")) + read(backend.resolve("build.gradle.kts")) + read(backend.resolve("pom.xml"));
        if (build.contains("spring-boot")) {
            s.framework = "Spring Boot (" + (build.contains("<project") ? "Maven" : "Gradle") + ")";
        } else if (!build.isEmpty()) {
            s.framework = build.contains("<project") ? "Java (Maven)" : "Java (Gradle)";
        } else if (Files.exists(dir.resolve("package.json"))) {
            s.framework = "Node.js";
        }
        s.appPort = serverPort(backend.resolve("src/main/resources"));
        if (s.appPort.isEmpty() && s.framework.startsWith("Spring")) {
            s.appPort = "8080";
        }
        for (Path f : composeCandidates(dir)) {
            s.composeFiles.add(dir.relativize(f).toString());
            s.services.addAll(parseCompose(f));
        }
        s.hasK6 = Files.isDirectory(dir.resolve("k6"));
        s.hasLogs = Files.isDirectory(dir.resolve("logs"));
        s.hasEvidence = Files.isDirectory(dir.resolve("docs/evidence"));
        return s;
    }

    public Service find(String... words) {
        for (Service sv : services) {
            if (sv.is(words)) {
                return sv;
            }
        }
        return null;
    }

    private static List<Path> composeCandidates(Path dir) throws IOException {
        List<Path> out = new ArrayList<Path>();
        for (Path d : new Path[]{dir, dir.resolve("monitoring"), dir.resolve("docker")}) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*compose*.{yml,yaml}")) {
                for (Path p : ds) {
                    if (!secretLike(p)) {
                        out.add(p);
                    }
                }
            }
        }
        out.sort(null);
        return out;
    }

    static boolean secretLike(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.startsWith(".env") || n.contains("secret") || n.contains(".my.cnf") || n.contains("credential");
    }

    /** server.port 만 찾는다. application-local 같은 개인 설정 파일은 열지 않는다 */
    static String serverPort(Path resources) throws IOException {
        for (String n : new String[]{"application.yml", "application.yaml", "application.properties"}) {
            String text = read(resources.resolve(n));
            Matcher m = Pattern.compile("(?m)^\\s*(?:server\\.)?port\\s*[:=]\\s*\\$?\\{?[A-Z_]*:?(\\d{2,5})").matcher(text);
            if (text.contains("server") && m.find()) {
                return m.group(1);
            }
        }
        return "";
    }

    /** docker-compose를 줄 단위로 읽어 서비스 이름, 이미지, 컨테이너 이름, 포트, MYSQL_DATABASE/POSTGRES_DB만 뽑는다 */
    static List<Service> parseCompose(Path f) throws IOException {
        List<Service> out = new ArrayList<Service>();
        boolean inServices = false, inPorts = false;
        Service cur = null;
        Pattern port = Pattern.compile("\"?'?(\\d+(?::\\d+)+)");
        for (String raw : read(f).split("\n")) {
            String line = raw.replace("\t", "    ");
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.replaceAll("^\\s+", "").length();
            String t = line.trim();
            if (indent == 0) {
                inServices = t.startsWith("services:");
                cur = null;
                continue;
            }
            if (!inServices) {
                continue;
            }
            if (indent <= 2 && t.endsWith(":") && !t.startsWith("-")) {
                cur = new Service();
                cur.name = t.substring(0, t.length() - 1);
                out.add(cur);
                inPorts = false;
                continue;
            }
            if (cur == null) {
                continue;
            }
            if (t.toLowerCase(Locale.ROOT).contains("password")) {
                continue;   // 비밀번호 줄은 읽지 않는다
            }
            if (t.startsWith("image:")) {
                cur.image = t.substring(6).trim();
            } else if (t.startsWith("container_name:")) {
                cur.container = t.substring(15).trim().replace("\"", "");
            } else if (t.startsWith("ports:")) {
                Matcher m = port.matcher(t.substring(6));
                while (m.find()) {
                    cur.ports.add(m.group(1));
                }
                inPorts = t.substring(6).trim().isEmpty();
                continue;
            } else if (inPorts && t.startsWith("-")) {
                Matcher m = port.matcher(t);
                if (m.find()) {
                    cur.ports.add(m.group(1));
                }
                continue;
            } else if (t.matches("-?\\s*(MYSQL_DATABASE|POSTGRES_DB)\\s*[:=].*")) {
                cur.database = t.replaceAll("^-?\\s*(MYSQL_DATABASE|POSTGRES_DB)\\s*[:=]\\s*", "").replace("\"", "").trim();
            }
            inPorts = false;
        }
        return out;
    }

    private static String read(Path p) throws IOException {
        if (!Files.isRegularFile(p) || secretLike(p)) {
            return "";
        }
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** AGENTS.md 초안 */
    public String draft() {
        Map<String, String> todo = new LinkedHashMap<String, String>();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append(" — AI 작업 지침\n\n");
        sb.append("> kit init 이 만든 초안이다. \"(확인 필요)\"를 채우고 틀린 값을 고친 뒤 이 줄을 지운다.\n\n");
        sb.append("## 구성\n");
        sb.append("| 대상 | 주소·이름 |\n|---|---|\n");
        if (!framework.isEmpty()) {
            sb.append("| 앱 | ").append(framework).append(", http://localhost:").append(appPort.isEmpty() ? "(확인 필요)" : appPort).append(" |\n");
        }
        Service db = find("mysql", "mariadb", "postgres");
        Service prom = find("prometheus");
        Service graf = find("grafana");
        Service redis = find("redis");
        if (db != null) {
            String kind = db.is("postgres") ? "PostgreSQL" : "MySQL";
            sb.append("| DB | ").append(kind).append(", 컨테이너 `").append(db.container.isEmpty() ? db.name : db.container)
                    .append("`, 포트 ").append(db.hostPort(db.is("postgres") ? "5432" : "3306"))
                    .append(db.database.isEmpty() ? "" : ", DB `" + db.database + "`").append(" |\n");
        }
        if (redis != null) {
            sb.append("| Redis | 컨테이너 `").append(redis.container.isEmpty() ? redis.name : redis.container).append("`, 포트 ").append(redis.hostPort("6379")).append(" |\n");
        }
        if (prom != null) {
            sb.append("| Prometheus | http://localhost:").append(prom.hostPort("9090")).append(" |\n");
        }
        if (graf != null) {
            sb.append("| Grafana | http://localhost:").append(graf.hostPort("3000")).append(" |\n");
        }
        if (hasLogs) {
            sb.append("| 로그 | `logs/` |\n");
        }
        if (!composeFiles.isEmpty()) {
            sb.append("\n실행 구성: ").append(String.join(", ", composeFiles)).append("\n");
        }
        sb.append("\n## 팀 스킬 설정\n");
        if (db != null && !db.is("postgres")) {
            sb.append("DB 조회(incident-analysis, query-analysis의 `db_query.sh`)는 이 값으로 실행한다:\n```bash\n");
            sb.append("export INCIDENT_DB_CONTAINER=").append(db.container.isEmpty() ? db.name : db.container).append("\n");
            if (!db.database.isEmpty()) {
                sb.append("export INCIDENT_DB_NAME=").append(db.database).append("\n");
            }
            sb.append("export INCIDENT_DB_CNF=(확인 필요: 읽기 전용 계정의 접속 정보 파일 경로)   # AI는 이 파일을 열지 않는다\n```\n");
        } else if (db != null) {
            todo.put("DB", "PostgreSQL은 db_query.sh가 아직 지원하지 않는다. DB 조회는 사람이 실행해 결과를 붙인다");
        }
        if (prom != null) {
            sb.append("지표 조회(`prom_query.py`): `PROM_URL=http://localhost:").append(prom.hostPort("9090")).append("`\n");
        }
        if (hasK6) {
            sb.append("부하 테스트 스크립트: `k6/`. 새 스크립트는 기존 관례를 따른다. 실행은 사람이 한다\n");
        }
        sb.append("성능 증거: `docs/evidence/<날짜>-<주제>/`").append(hasEvidence ? "" : " (폴더 없음, 처음 쓸 때 만든다)").append(", 보고서: `docs/perf/reports/`\n");
        sb.append("\n## 규칙\n");
        sb.append("- 분석할 때는 읽기만 한다. 재시작, 배포, 설정 변경, DB 쓰기·DDL은 사람이 승인한 뒤에만 한다\n");
        sb.append("- 비밀값 파일(.env, 접속 정보 파일, *-local 설정)은 열지 않는다\n");
        sb.append("\n## 알려진 사항 (확인 필요)\n");
        sb.append("- 주요 API와 자주 보는 지표 쿼리\n- 과거 장애·성능 문제와 원인\n- 테스트 계정·데이터 준비 방법\n");
        for (Map.Entry<String, String> e : todo.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }
}
