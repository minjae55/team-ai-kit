package kit.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 지침 파일 안의 "자동 관리 구역"만 고친다. 구역 밖에 사용자가 쓴 내용은 절대 건드리지 않는다.
 * 파일을 처음 바꾸기 전에 <파일>.kit-backup 으로 백업한다.
 */
public final class ManagedBlock {

    public static final String START = "<!-- team-ai-kit:start (자동 관리 구역, 직접 수정하지 마세요) -->";
    public static final String START_PREFIX = "<!-- team-ai-kit:start";
    public static final String END = "<!-- team-ai-kit:end -->";

    private ManagedBlock() {}

    public static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean has(Path file) throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        String text = read(file);
        return text.contains(START_PREFIX) && text.contains(END);
    }

    /** 관리 구역의 본문. 구역이 없으면 null. */
    public static String body(Path file) throws IOException {
        if (!has(file)) {
            return null;
        }
        String text = read(file);
        int s = text.indexOf(START_PREFIX);
        int bodyStart = text.indexOf('\n', s) + 1;
        int e = text.indexOf(END, bodyStart);
        return text.substring(bodyStart, e).replaceAll("\\s+$", "");
    }

    /** 구역을 넣거나 바꾼다. 바뀐 게 있으면 true. 파일이 없으면 새로 만든다. */
    public static boolean upsert(Path file, String body) throws IOException {
        String block = START + "\n" + body.replaceAll("\\s+$", "") + "\n" + END + "\n";
        if (!Files.exists(file)) {
            write(file, block);
            return true;
        }
        String text = read(file);
        String next;
        if (text.contains(START_PREFIX) && text.contains(END)) {
            int s = text.indexOf(START_PREFIX);
            int e = text.indexOf(END, s) + END.length();
            if (e < text.length() && text.charAt(e) == '\n') {
                e++;
            }
            next = text.substring(0, s) + block + text.substring(e);
        } else {
            String sep = text.isEmpty() ? "" : (text.endsWith("\n") ? "\n" : "\n\n");
            next = text + sep + block;
        }
        if (next.equals(text)) {
            return false;
        }
        backupOnce(file);
        write(file, next);
        return true;
    }

    /** 구역을 지운다. 구역을 지운 뒤 파일이 비고 deleteIfEmpty면 파일도 지운다. */
    public static boolean remove(Path file, boolean deleteIfEmpty) throws IOException {
        if (!has(file)) {
            return false;
        }
        String text = read(file);
        int s = text.indexOf(START_PREFIX);
        int e = text.indexOf(END, s) + END.length();
        if (e < text.length() && text.charAt(e) == '\n') {
            e++;
        }
        String next = (text.substring(0, s) + text.substring(e)).replaceAll("\\n{3,}", "\n\n");
        if (next.trim().isEmpty() && deleteIfEmpty) {
            Files.delete(file);
        } else {
            write(file, next);
        }
        return true;
    }

    private static void backupOnce(Path file) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".kit-backup");
        if (!Files.exists(backup)) {
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }
}
