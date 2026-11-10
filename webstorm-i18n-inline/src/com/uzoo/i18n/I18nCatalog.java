package com.uzoo.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressManager;

final class I18nCatalog {
    private static final Map<String, I18nCatalog> INSTANCES = new ConcurrentHashMap<>();
    private final Path projectRoot;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    private volatile long lastRefresh;
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Runnable onUpdated;
    private static final long REFRESH_INTERVAL_MS = 15_000;
    private static final Pattern TRANSLATION_CALL = Pattern.compile("(?<![\\w$])(?:\\$t|t)\\s*\\(\\s*(['\\\"`])([\\w.-]+)\\1\\s*\\)");

    record Definition(String key, String locale, String message, Path file, int offset, int keyStart, int keyEnd) { }
    record Usage(Path file, int offset) { }

    static I18nCatalog forProject(String root) {
        return INSTANCES.computeIfAbsent(root, I18nCatalog::new);
    }

    private I18nCatalog(String root) {
        projectRoot = Path.of(root);
    }

    String find(String key) {
        return find(key, "zh-CN");
    }

    String find(String key, String locale) {
        Definition definition = findDefinition(key, locale);
        if (definition == null && !"zh-CN".equals(locale)) definition = findDefinition(key, "zh-CN");
        return definition == null ? null : definition.message();
    }

    Definition findDefinition(String key) {
        return findDefinition(key, "zh-CN");
    }

    Definition findDefinition(String key, String locale) {
        refreshAsync(null);
        return snapshot.translations().getOrDefault(key, Map.of()).get(locale);
    }

    Definition findDefinitionAt(Path file, int offset) {
        refreshAsync(null);
        List<Definition> definitions = snapshot.definitionsByFile().get(file.toAbsolutePath().normalize());
        if (definitions == null) return null;
        int low = 0;
        int high = definitions.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Definition candidate = definitions.get(middle);
            if (offset < candidate.keyStart()) high = middle - 1;
            else if (offset >= candidate.keyEnd()) low = middle + 1;
            else return candidate;
        }
        return null;
    }

    List<Definition> findTranslations(String key) {
        refreshAsync(null);
        return snapshot.translations().getOrDefault(key, Map.of()).values().stream()
                .sorted(Comparator.comparing(Definition::locale))
                .toList();
    }

    List<String> availableLocales() {
        Set<String> locales = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        snapshot.translations().values().forEach(translations -> locales.addAll(translations.keySet()));

        Path modules = projectRoot.resolve("src/app");
        if (Files.isDirectory(modules)) {
            try (var directories = Files.list(modules)) {
                directories.filter(Files::isDirectory)
                        .forEach(directory -> collectLocaleNames(directory.resolve("locales"), locales));
            } catch (IOException ignored) { }
        }
        collectLocaleNames(projectRoot.resolve("src/i18n/locales"), locales);
        return List.copyOf(locales);
    }

    private static void collectLocaleNames(Path directory, Set<String> locales) {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .forEach(locales::add);
        } catch (IOException ignored) { }
    }

    List<Usage> findUsages(String key) {
        return scanUsages(key);
    }

    void refreshAsync(Runnable callback) {
        if (callback != null) onUpdated = callback;
        long now = System.currentTimeMillis();
        if (now - lastRefresh < REFRESH_INTERVAL_MS || !loading.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Snapshot snapshot = load();
                this.snapshot = snapshot;
                lastRefresh = System.currentTimeMillis();
            } finally {
                loading.set(false);
            }
            Runnable updated = onUpdated;
            if (updated != null) ApplicationManager.getApplication().invokeLater(updated);
        });
    }

    private Snapshot load() {
        Map<String, Map<String, Definition>> loaded = new HashMap<>();
        Map<Path, List<Definition>> byFile = new HashMap<>();
        Path modules = projectRoot.resolve("src/app");
        if (Files.isDirectory(modules)) {
            try (var dirs = Files.list(modules)) {
                dirs.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString))
                        .forEach(dir -> readLocales(dir.resolve("locales"), loaded, byFile));
            } catch (IOException ignored) { }
        }
        readLocales(projectRoot.resolve("src/i18n/locales"), loaded, byFile);
        loaded.replaceAll((key, translations) -> Map.copyOf(translations));
        byFile.replaceAll((file, definitions) -> definitions.stream().sorted(Comparator.comparingInt(Definition::keyStart)).toList());
        return new Snapshot(Map.copyOf(loaded), Map.copyOf(byFile));
    }

    private record Snapshot(Map<String, Map<String, Definition>> translations, Map<Path, List<Definition>> definitionsByFile) { }

    private List<Usage> scanUsages(String key) {
        Path sourceRoot = projectRoot.resolve("src");
        if (!Files.isDirectory(sourceRoot)) return List.of();
        List<Usage> usages = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".vue"))
                    .forEach(file -> {
                        ProgressManager.checkCanceled();
                        scanFile(file, key, usages);
                    });
        } catch (IOException ignored) { }
        usages.sort(Comparator.comparing((Usage usage) -> projectRoot.relativize(usage.file()).toString())
                .thenComparingInt(Usage::offset));
        return usages;
    }

    private void scanFile(Path file, String key, List<Usage> output) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            Matcher matcher = TRANSLATION_CALL.matcher(source);
            while (matcher.find()) {
                ProgressManager.checkCanceled();
                if (!key.equals(matcher.group(2))) continue;
                int offset = matcher.start(2);
                output.add(new Usage(file.toAbsolutePath().normalize(), offset));
            }
        } catch (IOException ignored) { }
    }

    private static void readLocales(Path directory, Map<String, Map<String, Definition>> output, Map<Path, List<Definition>> byFile) {
        if (!Files.isDirectory(directory)) return;
        try {
            try (var files = Files.list(directory)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(file -> read(file, output, byFile));
            }
        } catch (IOException ignored) { }
    }

    private static void read(Path file, Map<String, Map<String, Definition>> output, Map<Path, List<Definition>> byFile) {
        String fileName = file.getFileName().toString();
        String locale = fileName.substring(0, fileName.length() - ".json".length());
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            Object parsed = new JsonReader(content).parse();
            flatten(parsed, "", null, locale, file, output, byFile);
        } catch (RuntimeException | IOException ignored) { }
    }

    private static void flatten(Object value, String prefix, SourceRange keyRange, String locale, Path file,
                                Map<String, Map<String, Definition>> output, Map<Path, List<Definition>> byFile) {
        if (value instanceof JsonObject object) {
            object.forEach((key, child) -> flatten(child, prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key,
                    object.keyRanges.get(String.valueOf(key)), locale, file, output, byFile));
        } else if (value instanceof ParsedString message && !prefix.isEmpty()) {
            Definition definition = new Definition(prefix, locale, message.value(), file, message.contentOffset(),
                    keyRange == null ? -1 : keyRange.start(), keyRange == null ? -1 : keyRange.end());
            output.computeIfAbsent(prefix, ignored -> new HashMap<>()).put(locale, definition);
            byFile.computeIfAbsent(file.toAbsolutePath().normalize(), ignored -> new ArrayList<>()).add(definition);
        }
    }

    private record SourceRange(int start, int end) { }
    private record ParsedString(String value, int contentOffset, int contentEndOffset) { }

    private static final class JsonObject extends HashMap<String, Object> {
        private final Map<String, SourceRange> keyRanges = new HashMap<>();
    }

    private static final class JsonReader {
        private final String input;
        private int index;

        JsonReader(String input) { this.input = input; }

        Object parse() {
            Object value = value();
            whitespace();
            if (index != input.length()) throw new IllegalArgumentException("Trailing JSON content");
            return value;
        }

        private Object value() {
            whitespace();
            if (index >= input.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            return switch (input.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private JsonObject object() {
            JsonObject result = new JsonObject();
            index++;
            whitespace();
            if (take('}')) return result;
            do {
                whitespace();
                ParsedString parsedKey = string();
                String key = parsedKey.value();
                whitespace();
                require(':');
                result.put(key, value());
                result.keyRanges.put(key, new SourceRange(parsedKey.contentOffset(), parsedKey.contentEndOffset()));
                whitespace();
            } while (take(','));
            require('}');
            return result;
        }

        private List<Object> array() {
            List<Object> result = new ArrayList<>();
            index++;
            whitespace();
            if (take(']')) return result;
            do { result.add(value()); whitespace(); } while (take(','));
            require(']');
            return result;
        }

        private ParsedString string() {
            require('"');
            int contentOffset = index;
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char ch = input.charAt(index++);
                if (ch == '"') return new ParsedString(result.toString(), contentOffset, index - 1);
                if (ch != '\\') { result.append(ch); continue; }
                if (index >= input.length()) throw new IllegalArgumentException("Invalid escape");
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > input.length()) throw new IllegalArgumentException("Invalid unicode escape");
                        result.append((char) Integer.parseInt(input.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape");
                }
            }
            throw new IllegalArgumentException("Unclosed string");
        }

        private Object number() {
            int start = index;
            while (index < input.length() && "-+0123456789.eE".indexOf(input.charAt(index)) >= 0) index++;
            if (start == index) throw new IllegalArgumentException("Invalid value");
            return Double.parseDouble(input.substring(start, index));
        }

        private Object literal(String token, Object value) {
            if (!input.startsWith(token, index)) throw new IllegalArgumentException("Invalid literal");
            index += token.length();
            return value;
        }

        private void whitespace() { while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++; }
        private boolean take(char expected) { if (index < input.length() && input.charAt(index) == expected) { index++; return true; } return false; }
        private void require(char expected) { if (!take(expected)) throw new IllegalArgumentException("Expected " + expected); }
    }
}
