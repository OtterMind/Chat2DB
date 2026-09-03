package ai.chat2db.plugin.mysql.diagnostics;

import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbDeadlockSummary;
import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbDeadlockTransaction;
import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbParserMessage;
import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusResponse;
import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusSection;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InnodbStatusParser {

    private static final Set<String> RECOGNIZED_SECTIONS = Set.of(
            "INNODB MONITOR OUTPUT",
            "BACKGROUND THREAD",
            "SEMAPHORES",
            "LATEST FOREIGN KEY ERROR",
            "LATEST DETECTED DEADLOCK",
            "TRANSACTIONS",
            "FILE I/O",
            "INSERT BUFFER AND ADAPTIVE HASH INDEX",
            "LOG",
            "BUFFER POOL AND MEMORY",
            "ROW OPERATIONS",
            "END OF INNODB MONITOR OUTPUT"
    );
    private static final Pattern TRANSACTION_START = Pattern.compile("^\\*\\*\\* \\((\\d+)\\) TRANSACTION:");
    private static final Pattern LOCK_HELD_START = Pattern.compile("^\\*\\*\\* \\((\\d+)\\) HOLDS THE LOCK\\(S\\):");
    private static final Pattern LOCK_WAIT_START = Pattern.compile("^\\*\\*\\* \\((\\d+)\\) WAITING FOR THIS LOCK TO BE GRANTED:");
    private static final Pattern VICTIM = Pattern.compile("^\\*\\*\\* WE ROLL BACK TRANSACTION \\((\\d+)\\)");
    private static final Pattern TRANSACTION_ID = Pattern.compile("^TRANSACTION\\s+([^,\\s]+)(?:.*?ACTIVE\\s+(\\d+)\\s+sec)?.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern THREAD_ID = Pattern.compile("MySQL thread id\\s+(\\d+).*?query id\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:password|passwd|pwd|secret|token|api[_-]?key)\\b\\s*=\\s*)('[^']*'|\"[^\"]*\"|[^\\s,;]+)");
    private static final Pattern IDENTIFIED_BY = Pattern.compile("(?i)(\\bIDENTIFIED\\s+BY\\s+)('[^']*'|\"[^\"]*\"|\\S+)");

    private InnodbStatusParser() {
    }

    static InnodbStatusResponse parse(String rawText) {
        InnodbStatusResponse response = new InnodbStatusResponse();
        response.setRawText(redactApiVisibleText(rawText));
        response.setCapturedAt(Instant.now().toString());

        if (StringUtils.isBlank(rawText)) {
            response.getMessages().add(message("WARN", "EMPTY_OUTPUT",
                    "SHOW ENGINE INNODB STATUS returned no status text.", null, null));
            response.setLatestDeadlock(noDeadlockSummary());
            return response;
        }

        String normalizedText = normalizeNewlines(redactApiVisibleText(rawText));
        List<String> lines = List.of(normalizedText.split("\\n", -1));
        List<InnodbStatusSection> sections = parseSections(lines);
        if (sections.isEmpty()) {
            InnodbStatusSection fallback = section("Raw output", "RAW OUTPUT", false, 1, lines.size(), normalizedText);
            sections.add(fallback);
            response.getMessages().add(message("WARN", "UNKNOWN_FORMAT",
                    "The InnoDB status output did not contain recognizable monitor section delimiters.",
                    fallback.getTitle(), 1));
        }
        sections.stream()
                .filter(section -> !section.isRecognized())
                .forEach(section -> response.getMessages().add(message("INFO", "UNKNOWN_SECTION",
                        "The section was preserved as raw text because it is not a known InnoDB Monitor section.",
                        section.getTitle(), section.getStartLine())));
        if (StringUtils.containsIgnoreCase(normalizedText, "truncated")) {
            response.getMessages().add(message("WARN", "POSSIBLY_TRUNCATED",
                    "The server output mentions truncation; structured data may be incomplete.", null, null));
        }

        response.setSections(sections);
        response.setLatestDeadlock(parseLatestDeadlock(sections, response.getMessages()));
        return response;
    }

    private static List<InnodbStatusSection> parseSections(List<String> lines) {
        List<SectionHeader> headers = new ArrayList<>();
        for (int i = 0; i + 2 < lines.size(); i++) {
            if (i + 3 < lines.size() && isSeparator(lines.get(i)) && looksLikeSectionHeading(lines.get(i + 2))
                    && isSeparator(lines.get(i + 3))) {
                headers.add(new SectionHeader(i, lines.get(i + 2).trim()));
                i += 3;
                continue;
            }
            String title = lines.get(i + 1).trim();
            if (isSeparator(lines.get(i)) && looksLikeSectionHeading(title)
                    && isSeparator(lines.get(i + 2))) {
                headers.add(new SectionHeader(i, title));
                i += 2;
            }
        }
        List<InnodbStatusSection> sections = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            SectionHeader header = headers.get(i);
            int endExclusive = i + 1 < headers.size() ? headers.get(i + 1).lineIndex() : lines.size();
            String text = String.join("\n", lines.subList(header.lineIndex(), endExclusive));
            String normalizedTitle = normalizeTitle(header.title());
            sections.add(section(header.title(), normalizedTitle, RECOGNIZED_SECTIONS.contains(normalizedTitle),
                    header.lineIndex() + 1, endExclusive, text));
        }
        return sections;
    }

    private static InnodbDeadlockSummary parseLatestDeadlock(List<InnodbStatusSection> sections,
            List<InnodbParserMessage> messages) {
        InnodbStatusSection section = sections.stream()
                .filter(item -> "LATEST DETECTED DEADLOCK".equals(item.getNormalizedTitle()))
                .findFirst()
                .orElse(null);
        if (section == null) {
            return noDeadlockSummary();
        }

        String body = stripSectionHeader(section.getText());
        List<String> lines = List.of(body.split("\\n", -1));
        if (lines.stream().noneMatch(line -> TRANSACTION_START.matcher(line).find())) {
            InnodbDeadlockSummary summary = noDeadlockSummary();
            summary.setRawText(section.getText());
            return summary;
        }

        InnodbDeadlockSummary summary = new InnodbDeadlockSummary();
        summary.setFound(true);
        summary.setRawText(section.getText());
        summary.setMessage("Latest deadlock parsed from server monitor output.");
        summary.setTime(firstDeadlockTimestamp(lines));

        List<InnodbDeadlockTransaction> transactions = new ArrayList<>();
        InnodbDeadlockTransaction currentTransaction = null;
        LockCollector lockCollector = LockCollector.none();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher transactionMatcher = TRANSACTION_START.matcher(line);
            if (transactionMatcher.find()) {
                currentTransaction = new InnodbDeadlockTransaction();
                currentTransaction.setMarker(transactionMatcher.group(1));
                transactions.add(currentTransaction);
                lockCollector = LockCollector.none();
                continue;
            }

            Matcher victimMatcher = VICTIM.matcher(line);
            if (victimMatcher.find()) {
                summary.setVictimTransaction(victimMatcher.group(1));
                lockCollector = LockCollector.none();
                continue;
            }

            Matcher heldMatcher = LOCK_HELD_START.matcher(line);
            if (heldMatcher.find()) {
                currentTransaction = findTransaction(transactions, heldMatcher.group(1));
                lockCollector = new LockCollector(currentTransaction, true);
                continue;
            }

            Matcher waitMatcher = LOCK_WAIT_START.matcher(line);
            if (waitMatcher.find()) {
                currentTransaction = findTransaction(transactions, waitMatcher.group(1));
                lockCollector = new LockCollector(currentTransaction, false);
                continue;
            }

            if (line.startsWith("***")) {
                lockCollector = LockCollector.none();
                continue;
            }

            if (currentTransaction != null) {
                applyTransactionDetail(currentTransaction, line, lines, i);
                lockCollector.add(line);
            }
        }

        if (summary.getVictimTransaction() != null) {
            transactions.stream()
                    .filter(transaction -> summary.getVictimTransaction().equals(transaction.getMarker()))
                    .forEach(transaction -> transaction.setVictim(true));
        } else {
            messages.add(message("WARN", "DEADLOCK_VICTIM_NOT_FOUND",
                    "The latest deadlock section was found, but the victim transaction line was not recognized.",
                    section.getTitle(), section.getStartLine()));
        }
        if (transactions.isEmpty()) {
            messages.add(message("WARN", "DEADLOCK_TRANSACTIONS_NOT_FOUND",
                    "The latest deadlock section was found, but no transaction blocks were recognized.",
                    section.getTitle(), section.getStartLine()));
        }
        summary.setTransactions(transactions);
        return summary;
    }

    private static void applyTransactionDetail(InnodbDeadlockTransaction transaction, String line, List<String> lines,
            int lineIndex) {
        Matcher transactionIdMatcher = TRANSACTION_ID.matcher(line);
        if (transaction.getTransactionId() == null && transactionIdMatcher.find()) {
            transaction.setTransactionId(transactionIdMatcher.group(1));
            if (transactionIdMatcher.group(2) != null) {
                transaction.setActiveSeconds(Integer.parseInt(transactionIdMatcher.group(2)));
            }
        }

        Matcher threadMatcher = THREAD_ID.matcher(line);
        if (threadMatcher.find()) {
            transaction.setMysqlThreadId(threadMatcher.group(1));
            transaction.setQueryId(threadMatcher.group(2));
            String sql = nextSql(lines, lineIndex + 1);
            if (sql != null) {
                transaction.setSql(redactSummarySql(sql));
            }
        }
    }

    private static String nextSql(List<String> lines, int startIndex) {
        List<String> sqlLines = new ArrayList<>();
        for (int i = startIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("***") || line.startsWith("TABLE LOCK") || line.startsWith("RECORD LOCKS")) {
                break;
            }
            if (StringUtils.isBlank(line)) {
                if (!sqlLines.isEmpty()) {
                    break;
                }
                continue;
            }
            if (sqlLines.isEmpty() && !looksLikeSql(line)) {
                continue;
            }
            sqlLines.add(line.trim());
        }
        return sqlLines.isEmpty() ? null : String.join("\n", sqlLines);
    }

    private static boolean looksLikeSql(String line) {
        String upper = line.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("SELECT ") || upper.startsWith("UPDATE ") || upper.startsWith("INSERT ")
                || upper.startsWith("DELETE ") || upper.startsWith("REPLACE ") || upper.startsWith("ALTER ")
                || upper.startsWith("CREATE ") || upper.startsWith("DROP ") || upper.startsWith("CALL ")
                || upper.startsWith("WITH ");
    }

    private static String redactSummarySql(String sql) {
        return redactApiVisibleText(sql);
    }

    private static String redactApiVisibleText(String text) {
        if (text == null) {
            return null;
        }
        String redacted = SENSITIVE_ASSIGNMENT.matcher(text).replaceAll("$1<redacted>");
        return IDENTIFIED_BY.matcher(redacted).replaceAll("$1<redacted>");
    }

    private static InnodbDeadlockTransaction findTransaction(List<InnodbDeadlockTransaction> transactions,
            String marker) {
        return transactions.stream()
                .filter(transaction -> marker.equals(transaction.getMarker()))
                .findFirst()
                .orElse(null);
    }

    private static String firstDeadlockTimestamp(List<String> lines) {
        return lines.stream()
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .filter(line -> !isSeparator(line))
                .filter(line -> !line.equals("LATEST DETECTED DEADLOCK"))
                .filter(line -> !line.startsWith("***"))
                .findFirst()
                .orElse(null);
    }

    private static String stripSectionHeader(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\\n", -1)));
        if (lines.size() >= 3 && isSeparator(lines.get(0)) && isSeparator(lines.get(2))) {
            return String.join("\n", lines.subList(3, lines.size()));
        }
        return text;
    }

    private static InnodbDeadlockSummary noDeadlockSummary() {
        InnodbDeadlockSummary summary = new InnodbDeadlockSummary();
        summary.setFound(false);
        summary.setMessage("The server did not provide a latest deadlock.");
        return summary;
    }

    private static InnodbStatusSection section(String title, String normalizedTitle, boolean recognized, int startLine,
            int endLine, String text) {
        InnodbStatusSection section = new InnodbStatusSection();
        section.setTitle(title);
        section.setNormalizedTitle(normalizedTitle);
        section.setRecognized(recognized);
        section.setStartLine(startLine);
        section.setEndLine(endLine);
        section.setText(text);
        return section;
    }

    private static InnodbParserMessage message(String severity, String code, String message, String sectionTitle,
            Integer line) {
        InnodbParserMessage parserMessage = new InnodbParserMessage();
        parserMessage.setSeverity(severity);
        parserMessage.setCode(code);
        parserMessage.setMessage(message);
        parserMessage.setSectionTitle(sectionTitle);
        parserMessage.setLine(line);
        return parserMessage;
    }

    private static boolean isSeparator(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        char first = trimmed.charAt(0);
        if (first != '-' && first != '=') {
            return false;
        }
        for (int i = 1; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeTitle(String title) {
        return StringUtils.normalizeSpace(title).toUpperCase(Locale.ROOT);
    }

    private static boolean looksLikeSectionHeading(String title) {
        String trimmed = StringUtils.trimToEmpty(title);
        if (StringUtils.isBlank(trimmed) || isSeparator(trimmed)) {
            return false;
        }
        return trimmed.equals(trimmed.toUpperCase(Locale.ROOT))
                && trimmed.matches(".*[A-Z].*")
                && trimmed.matches("[A-Z0-9 /&().-]+");
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record SectionHeader(int lineIndex, String title) {
    }

    private record LockCollector(InnodbDeadlockTransaction transaction, boolean held) {

        static LockCollector none() {
            return new LockCollector(null, false);
        }

        void add(String line) {
            if (transaction == null || StringUtils.isBlank(line)) {
                return;
            }
            if (held) {
                transaction.getHeldLocks().add(line);
            } else {
                transaction.getWaitedLocks().add(line);
            }
        }
    }
}
