package com.platform.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化分块：优先按 Markdown 标题/段落/表格边界切分，字符数只作为兜底。
 *
 * <p>与旧实现“每 500 字符硬切”相比：
 * <ul>
 *   <li>表格、代码块保持原子性，不会被从中间切断；</li>
 *   <li>父块约 2000 字符，子块约 500 字符，长段落按句末回退并保留 50 字重叠；</li>
 *   <li>页码锚点 {@code <!-- page: N -->} 只进入父块，子块通过 pageFrom/pageTo 记录页码；</li>
 *   <li>embedding 文本使用去 Markdown/HTML 的纯文本，并在缺少标题时补上所属标题路径。</li>
 * </ul>
 */
final class MarkdownChunker {

    static final int CHILD_SIZE = 500;
    static final int CHILD_OVERLAP = 50;
    static final int PARENT_SIZE = 2000;
    static final int TABLE_PART_SIZE = 1500;

    private static final Pattern PAGE = Pattern.compile("^<!--\\s*page:\\s*(\\d+)\\s*-->$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[•·●○◦▪▫■□◆◇►▶‣⁃\\-–—*]\\s+.*");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d{1,3}[.)、．]\\s+.*");

    private MarkdownChunker() {
    }

    record Chunk(String content, String parentContent, int parentIndex,
                 String embeddingText, int pageFrom, int pageTo, List<String> headingPath) {
    }

    static List<Chunk> split(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) return List.of();
        List<Block> blocks = parseBlocks(text);
        List<List<Block>> parents = groupParents(blocks);
        List<Chunk> chunks = new ArrayList<>();
        int parentIndex = 0;
        for (List<Block> parent : parents) {
            String parentContent = render(parent);
            if (parentContent.isBlank()) continue;
            for (Child child : splitChildren(parent)) {
                chunks.add(new Chunk(child.content(), parentContent, parentIndex,
                        embeddingText(child), child.pageFrom(), child.pageTo(), child.headingPath()));
            }
            parentIndex++;
        }
        return chunks;
    }

    private enum Kind {PAGE, HEADING, TEXT, LIST, TABLE, CODE}

    private static final class Block {
        private final Kind kind;
        private final int level;
        private final String text;
        private final int page;
        private final List<String> headingPath;

        private Block(Kind kind, int level, String text, int page, List<String> headingPath) {
            this.kind = kind;
            this.level = level;
            this.text = text;
            this.page = page;
            this.headingPath = headingPath;
        }

        private boolean atomic() {
            return kind == Kind.TABLE || kind == Kind.CODE;
        }

        private int length() {
            return text == null ? 0 : text.length();
        }
    }

    private record Child(String content, int pageFrom, int pageTo, List<String> headingPath) {
    }

    private record Heading(int level, String title) {
    }

    private static List<Block> parseBlocks(String text) {
        String[] lines = text.split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        List<Heading> headings = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int page = 0;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushParagraph(blocks, paragraph, page, headings);
                i++;
                continue;
            }
            Matcher pageMatcher = PAGE.matcher(trimmed);
            if (pageMatcher.matches()) {
                flushParagraph(blocks, paragraph, page, headings);
                page = Integer.parseInt(pageMatcher.group(1));
                blocks.add(new Block(Kind.PAGE, 0, trimmed, page, path(headings)));
                i++;
                continue;
            }
            if (trimmed.startsWith("```")) {
                flushParagraph(blocks, paragraph, page, headings);
                StringBuilder code = new StringBuilder();
                code.append(lines[i]).append('\n');
                i++;
                while (i < lines.length) {
                    code.append(lines[i]).append('\n');
                    if (lines[i].trim().startsWith("```")) {
                        i++;
                        break;
                    }
                    i++;
                }
                for (String part : splitCode(code.toString().trim(), TABLE_PART_SIZE)) {
                    blocks.add(new Block(Kind.CODE, 0, part, page, path(headings)));
                }
                continue;
            }
            if (isTableStart(trimmed)) {
                flushParagraph(blocks, paragraph, page, headings);
                StringBuilder table = new StringBuilder();
                table.append(lines[i]).append('\n');
                i++;
                while (i < lines.length) {
                    table.append(lines[i]).append('\n');
                    if (lines[i].toLowerCase(Locale.ROOT).contains("</table>")) {
                        i++;
                        break;
                    }
                    i++;
                }
                for (String part : MarkdownText.splitTable(table.toString().trim(), TABLE_PART_SIZE)) {
                    blocks.add(new Block(Kind.TABLE, 0, part, page, path(headings)));
                }
                continue;
            }
            if (isPipeTableStart(lines, i)) {
                flushParagraph(blocks, paragraph, page, headings);
                StringBuilder table = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    table.append(lines[i].stripTrailing()).append('\n');
                    i++;
                }
                for (String part : MarkdownText.splitTable(table.toString().trim(), TABLE_PART_SIZE)) {
                    blocks.add(new Block(Kind.TABLE, 0, part, page, path(headings)));
                }
                continue;
            }
            Matcher headingMatcher = HEADING.matcher(trimmed);
            if (headingMatcher.matches()) {
                flushParagraph(blocks, paragraph, page, headings);
                int level = headingMatcher.group(1).length();
                String title = headingMatcher.group(2).trim();
                List<String> parentPath = path(headings);
                while (!headings.isEmpty() && headings.get(headings.size() - 1).level >= level) {
                    headings.remove(headings.size() - 1);
                }
                blocks.add(new Block(Kind.HEADING, level, "#".repeat(level) + " " + title,
                        page, parentPath));
                headings.add(new Heading(level, title));
                i++;
                continue;
            }
            if (isList(trimmed)) {
                flushParagraph(blocks, paragraph, page, headings);
                StringBuilder list = new StringBuilder();
                while (i < lines.length) {
                    String current = lines[i];
                    if (current.trim().isEmpty()) break;
                    if (isTableStart(current.trim()) || isPipeTableStart(lines, i)
                            || PAGE.matcher(current.trim()).matches()
                            || HEADING.matcher(current.trim()).matches()
                            || current.trim().startsWith("```")) break;
                    if (!isList(current.trim()) && !startsWithWhitespace(current)) break;
                    list.append(current.stripTrailing()).append('\n');
                    i++;
                }
                blocks.add(new Block(Kind.LIST, 0, list.toString().trim(), page, path(headings)));
                continue;
            }
            if (paragraph.length() > 0) paragraph.append('\n');
            paragraph.append(trimmed);
            i++;
        }
        flushParagraph(blocks, paragraph, page, headings);
        return blocks;
    }

    private static void flushParagraph(List<Block> blocks, StringBuilder paragraph,
                                       int page, List<Heading> headings) {
        if (paragraph.length() == 0) return;
        String value = paragraph.toString().trim();
        if (!value.isEmpty()) {
            blocks.add(new Block(Kind.TEXT, 0, value, page, path(headings)));
        }
        paragraph.setLength(0);
    }

    private static List<String> path(List<Heading> headings) {
        List<String> path = new ArrayList<>(headings.size());
        for (Heading heading : headings) path.add(heading.title());
        return path;
    }

    private static boolean isTableStart(String line) {
        return line.toLowerCase(Locale.ROOT).startsWith("<table");
    }

    private static boolean isPipeTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length) return false;
        String current = lines[index].trim();
        String next = lines[index + 1].trim();
        return current.startsWith("|") && next.matches("^\\|[-: |]+\\|$");
    }

    private static boolean isList(String line) {
        return BULLET.matcher(line).matches() || NUMBERED.matcher(line).matches();
    }

    private static boolean startsWithWhitespace(String value) {
        return !value.isEmpty() && Character.isWhitespace(value.charAt(0));
    }

    private static List<List<Block>> groupParents(List<Block> blocks) {
        List<List<Block>> parents = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        int length = 0;
        for (Block block : blocks) {
            if (block.kind == Kind.PAGE) {
                current.add(block);
                continue;
            }
            boolean headingNearLimit = block.kind == Kind.HEADING
                    && length > PARENT_SIZE * 0.8;
            boolean overLimit = length + block.length() + 2 > PARENT_SIZE;
            if (!current.isEmpty() && (headingNearLimit || (overLimit && !block.atomic()))) {
                flushParent(parents, current);
                current = new ArrayList<>();
                length = 0;
            }
            if (!current.isEmpty() && overLimit && block.atomic()) {
                flushParent(parents, current);
                current = new ArrayList<>();
                length = 0;
            }
            current.add(block);
            length += block.length() + 2;
            if (length >= PARENT_SIZE) {
                flushParent(parents, current);
                current = new ArrayList<>();
                length = 0;
            }
        }
        flushParent(parents, current);
        return parents;
    }

    private static void flushParent(List<List<Block>> parents, List<Block> current) {
        boolean hasContent = current.stream().anyMatch(block -> block.kind != Kind.PAGE);
        if (hasContent) parents.add(new ArrayList<>(current));
        current.clear();
    }

    private static String render(List<Block> blocks) {
        StringBuilder out = new StringBuilder();
        for (Block block : blocks) {
            if (block.kind == Kind.PAGE) {
                if (out.length() > 0) out.append("\n\n");
                out.append(block.text);
                continue;
            }
            if (out.length() > 0) out.append("\n\n");
            out.append(block.text);
        }
        return out.toString().trim();
    }

    private static List<Child> splitChildren(List<Block> blocks) {
        List<Child> children = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int page = 0;
        int startPage = 0;
        int endPage = 0;
        List<String> headingPath = List.of();

        for (Block block : blocks) {
            if (block.kind == Kind.PAGE) {
                if (buffer.length() > 0 && page != 0 && block.page != page) {
                    flushChild(children, buffer, startPage, endPage, headingPath);
                }
                page = block.page;
                if (buffer.length() == 0) startPage = page;
                continue;
            }
            headingPath = block.headingPath;
            if (block.atomic()) {
                flushChild(children, buffer, startPage, endPage, headingPath);
                children.add(new Child(block.text, block.page, block.page, headingPath));
                continue;
            }
            if (block.length() > CHILD_SIZE) {
                flushChild(children, buffer, startPage, endPage, headingPath);
                for (String part : splitText(block.text)) {
                    children.add(new Child(part, block.page, block.page, headingPath));
                }
                continue;
            }
            if (buffer.length() > 0 && buffer.length() + block.length() + 2 > CHILD_SIZE) {
                flushChild(children, buffer, startPage, endPage, headingPath);
            }
            if (buffer.length() == 0) {
                startPage = block.page;
                endPage = block.page;
            } else {
                buffer.append("\n\n");
            }
            buffer.append(block.text);
            endPage = Math.max(endPage, block.page);
        }
        flushChild(children, buffer, startPage, endPage, headingPath);
        return children;
    }

    private static void flushChild(List<Child> children, StringBuilder buffer,
                                   int startPage, int endPage, List<String> headingPath) {
        String content = buffer.toString().trim();
        if (!content.isEmpty()) {
            children.add(new Child(content, startPage, Math.max(startPage, endPage), headingPath));
        }
        buffer.setLength(0);
    }

    /** 超大代码块按行切分，每片保留原语言 fence，避免一个块撑爆父块和 embedding。 */
    private static List<String> splitCode(String code, int maxChars) {
        if (code == null || code.isBlank() || code.length() <= maxChars) {
            return List.of(code == null ? "" : code);
        }
        String[] lines = code.split("\n", -1);
        String open = lines.length > 0 ? lines[0] : "```";
        String close = lines.length > 0 && lines[lines.length - 1].trim().startsWith("```")
                ? lines[lines.length - 1].trim() : "```";
        int last = lines.length > 0 && lines[lines.length - 1].trim().startsWith("```")
                ? lines.length - 1 : lines.length;
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder(open).append('\n');
        boolean body = false;
        for (int i = 1; i < last; i++) {
            String line = lines[i];
            if (body && part.length() + line.length() + close.length() + 2 > maxChars) {
                part.append(close);
                parts.add(part.toString());
                part = new StringBuilder(open).append('\n');
                body = false;
            }
            part.append(line).append('\n');
            body = true;
        }
        part.append(close);
        if (body || parts.isEmpty()) parts.add(part.toString());
        return parts;
    }

    private static List<String> splitText(String text) {
        List<String> parts = new ArrayList<>();
        String value = text.trim();
        if (value.length() <= CHILD_SIZE) {
            if (!value.isEmpty()) parts.add(value);
            return parts;
        }
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(start + CHILD_SIZE, value.length());
            if (end < value.length()) {
                int boundary = sentenceBoundary(value, start, end);
                if (boundary > start + CHILD_SIZE / 2) end = boundary;
            }
            String part = value.substring(start, end).trim();
            if (!part.isEmpty()) parts.add(part);
            if (end >= value.length()) break;
            start = Math.max(start + 1, end - CHILD_OVERLAP);
        }
        return parts;
    }

    private static int sentenceBoundary(String value, int start, int end) {
        for (int i = end - 1; i > start + CHILD_SIZE / 2; i--) {
            char c = value.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == ';' || c == '；'
                    || c == '\n' || c == '.') {
                return i + 1;
            }
        }
        // 英文尽量按空格回退，避免把单词切成两半
        for (int i = end - 1; i > end - 40; i--) {
            if (Character.isWhitespace(value.charAt(i))) return i + 1;
        }
        return end;
    }

    private static String embeddingText(Child child) {
        String plain = MarkdownText.toPlain(child.content());
        if (plain.isBlank()) return plain;
        if (child.headingPath().isEmpty() || child.content().trim().startsWith("#")) {
            return plain;
        }
        return String.join(" > ", child.headingPath()) + "\n" + plain;
    }
}
