package kit.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 웹 AI(claude.ai, ChatGPT, Gemini)에 올릴 스킬 묶음을 만든다. 웹 AI는 외부에서 자동으로 갱신할 공개 기능이 없어서
 * 사람이 올리고, 키트는 올릴 내용과 "지난번 이후 바뀐 스킬"을 알려 준다.
 *
 * 결과: &lt;out&gt;/claude-web/&lt;스킬&gt;.zip, &lt;out&gt;/chatgpt/&lt;스킬&gt;/{instructions.md, files/…},
 *       &lt;out&gt;/gemini/&lt;스킬&gt;/{instructions.md, files/…}, &lt;out&gt;/manifest.json (스킬별 내용 지문)
 */
public final class WebExport {

    public static final List<String> TOOLS = java.util.Arrays.asList("claude-web", "chatgpt", "gemini");
    /** ChatGPT GPT 지침 칸 한도(8,000자)보다 조금 작게. 넘으면 짧은 지침 + 전체 절차는 첨부 파일로 */
    static final int INSTRUCTION_LIMIT = 7500;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static final class Entry {
        public String tool;
        public String skill;
        /** new: 처음, changed: 지난 내보내기 이후 바뀜, same: 그대로 */
        public String status;
        public String path;
    }

    private final KitEnv env;

    public WebExport(KitEnv env) {
        this.env = env;
    }

    public Path defaultOut() {
        return env.kitDir().resolve("export");
    }

    public List<Entry> run(Path out, Collection<String> tools, Collection<String> skip) throws IOException {
        Path manifestFile = out.resolve("manifest.json");
        JsonNode old = Files.exists(manifestFile) ? JSON.readTree(manifestFile.toFile()) : JSON.createObjectNode();
        ObjectNode manifest = old.isObject() ? ((ObjectNode) old).deepCopy() : JSON.createObjectNode();
        List<Entry> entries = new ArrayList<Entry>();
        List<String> skills = new Installer(env, null, true).repoSkills();
        for (String tool : tools) {
            if (!TOOLS.contains(tool)) {
                throw new IllegalArgumentException("알 수 없는 도구: " + tool + " (가능: " + String.join(", ", TOOLS) + ")");
            }
            ObjectNode toolHashes = manifest.has(tool) && manifest.get(tool).isObject()
                    ? (ObjectNode) manifest.get(tool) : manifest.putObject(tool);
            for (String skill : skills) {
                if (skip.contains(skill)) {
                    continue;
                }
                Path src = env.skillsSource().resolve(skill);
                String hash = hash(src);
                Entry e = new Entry();
                e.tool = tool;
                e.skill = skill;
                String before = toolHashes.path(skill).asText(null);
                e.status = before == null ? "new" : before.equals(hash) ? "same" : "changed";
                Path target;
                if ("claude-web".equals(tool)) {
                    target = out.resolve(tool).resolve(skill + ".zip");
                    zip(src, skill, target);
                } else {
                    target = out.resolve(tool).resolve(skill);
                    writeChatBundle(tool, skill, src, target);
                }
                e.path = target.toString();
                toolHashes.put(skill, hash);
                entries.add(e);
            }
        }
        Files.createDirectories(out);
        JSON.writeValue(manifestFile.toFile(), manifest);
        return entries;
    }

    /** claude.ai 스킬 업로드 형식: 스킬 폴더째 담은 zip */
    static void zip(Path src, String skill, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream os = Files.newOutputStream(target); ZipOutputStream z = new ZipOutputStream(os)) {
            for (Path f : files(src)) {
                z.putNextEntry(new ZipEntry(skill + "/" + src.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, z);
                z.closeEntry();
            }
        }
    }

    /** ChatGPT(GPT·프로젝트)와 Gemini(Gem)용: 지침 칸에 붙여 넣을 instructions.md와 지식 파일로 올릴 files/ */
    void writeChatBundle(String tool, String skill, Path src, Path target) throws IOException {
        deleteTree(target);
        Path filesDir = target.resolve("files");
        Files.createDirectories(filesDir);
        List<String> attached = new ArrayList<String>();
        for (Path f : files(src)) {
            String flat = src.relativize(f).toString().replace('\\', '/').replace('/', '_');  // 웹은 폴더 없이 올라간다
            Files.copy(f, filesDir.resolve(flat));
            attached.add(flat);
        }
        String skillMd = new String(Files.readAllBytes(src.resolve("SKILL.md")), StandardCharsets.UTF_8);
        String description = frontmatter(skillMd, "description");
        String body = stripFrontmatter(skillMd);
        String product = "chatgpt".equals(tool) ? "ChatGPT" : "Gemini";
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(skill).append(" (팀 AI 키트 스킬, ").append(product).append("용)\n\n");
        sb.append(description).append("\n\n");
        sb.append("## 웹에서 쓸 때의 규칙\n");
        sb.append("- 이 지침의 `scripts/…`, `references/…` 파일은 첨부 파일로 올라와 있다. 경로의 `/`는 `_`로 바뀌었다 (예: `scripts/x.py` → `scripts_x.py`).\n");
        if ("chatgpt".equals(tool)) {
            sb.append("- Python 스크립트(`.py`)는 코드 실행(데이터 분석) 기능으로 첨부 파일을 그대로 실행한다. 판정을 눈으로 대신하지 않는다.\n");
        } else {
            sb.append("- Python 스크립트(`.py`)는 코드 실행이 가능하면 첨부 파일을 그대로 실행하고, 안 되면 사용자에게 자기 PC에서 실행해 결과를 붙여 달라고 한다. 판정을 눈으로 대신하지 않는다.\n");
        }
        sb.append("- 사용자 PC나 회사 시스템에 접속해야 하는 단계(셸 스크립트, DB·지표 조회)는 웹에서 할 수 없다. 실행할 명령을 알려 주고 결과를 붙여 달라고 한다.\n");
        sb.append("- 프로젝트·업무 기준(AGENTS.md에 해당하는 내용)은 사용자에게 묻거나 사용자가 올린 파일에서 찾는다.\n\n");
        boolean fits = sb.length() + body.length() + 40 <= INSTRUCTION_LIMIT;
        if (fits) {
            sb.append("## 절차\n").append(body.trim()).append("\n");
        } else {
            sb.append("## 절차\n전체 절차는 첨부 파일 `SKILL.md`를 따른다. 작업을 시작하기 전에 그 파일을 먼저 읽는다.\n");
        }
        Files.write(target.resolve("instructions.md"), sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder how = new StringBuilder();
        if ("chatgpt".equals(tool)) {
            how.append("ChatGPT에 올리는 법\n1. GPT 만들기(또는 프로젝트) → 지침(Instructions) 칸에 instructions.md 내용을 붙여 넣는다\n");
            how.append("2. 지식(Knowledge)·파일에 files/ 안의 파일을 모두 올린다\n3. 기능에서 코드 실행(Code Interpreter & Data Analysis)을 켠다\n");
        } else {
            how.append("Gemini에 올리는 법\n1. Gem 만들기 → 안내(Instructions) 칸에 instructions.md 내용을 붙여 넣는다\n");
            how.append("2. 지식(Knowledge)에 files/ 안의 파일을 올린다\n");
        }
        how.append("스킬이 바뀌면 kit export 가 '바뀜'으로 알려 준다. 그때 지침과 파일을 다시 올린다.\n");
        Files.write(target.resolve("올리는법.txt"), how.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String hash(Path dir) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path f : files(dir)) {
                md.update(dir.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                md.update(Files.readAllBytes(f));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.toString().contains("__pycache__"))
                    .sorted().collect(Collectors.toList());
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(x);
            }
        }
    }

    static String stripFrontmatter(String md) {
        if (md.startsWith("---")) {
            int end = md.indexOf("\n---", 3);
            if (end > 0) {
                return md.substring(md.indexOf('\n', end + 1) + 1);
            }
        }
        return md;
    }

    static String frontmatter(String md, String key) {
        for (String line : md.split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return "";
    }
}
