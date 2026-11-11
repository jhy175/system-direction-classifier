package cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition;

import javax.xml.stream.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static cn.edu.whu.irlab.systemdirectionclassifier.manager.recognition.RecognitionStore.invalid;

/** Reads the bounded CSV and XLSX data used by the batch wizard. */
final class BatchInputFile {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_ROWS = 5000;
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_EXPANDED = 48 * 1024 * 1024;
    final String name, encoding, sheet;
    final byte[] content;
    final List<String> sheets, columns;
    final List<Row> rows;

    private BatchInputFile(String name, byte[] content, String encoding, String sheet, List<String> sheets, List<Row> source) {
        this.name = name; this.content = content; this.encoding = encoding; this.sheet = sheet; this.sheets = sheets;
        columns = new ArrayList<>();
        if (source.isEmpty()) {
            if (sheet == null) throw invalid("文件缺少表头和数据");
            rows = Collections.emptyList();
            return;
        }
        List<String> header = source.get(0).cells;
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < header.size(); index++) {
            String title = header.get(index).trim();
            if (title.isEmpty()) title = "未命名列 " + (index + 1);
            if (title.length() > 255) throw invalid("列名过长");
            if (!unique.add(title)) throw invalid("表头中有重复列名：" + title);
            columns.add(title);
        }
        rows = source.subList(1, source.size());
    }

    static BatchInputFile read(String name, byte[] data, String encoding, String sheet) {
        String suffix = name.toLowerCase(Locale.ROOT);
        if (suffix.endsWith(".csv")) {
            String charset = encoding.isEmpty() ? "UTF-8" : encoding;
            if (!Arrays.asList("UTF-8", "GB18030").contains(charset)) throw invalid("CSV 编码可选择 UTF-8 或 GB18030");
            return new BatchInputFile(name, data, charset, null, Collections.emptyList(), csv(decode(data, charset)));
        }
        if (!suffix.endsWith(".xlsx")) throw invalid("请选择 CSV 或 XLSX 文件");
        try {
            Map<String,byte[]> entries = workbookEntries(data);
            Map<String,String> targets = new HashMap<>();
            XMLStreamReader relationships = xml(entries, "xl/_rels/workbook.xml.rels");
            try {
                while (relationships.hasNext()) {
                    if (relationships.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(relationships.getLocalName())
                        && !"External".equals(attribute(relationships, "TargetMode"))) {
                        String target = attribute(relationships, "Target");
                        if (!target.isEmpty()) {
                            String path = URI.create("/xl/workbook.xml").resolve(target).normalize().getPath();
                            if (path != null) targets.put(attribute(relationships, "Id"), path.replaceFirst("^/", ""));
                        }
                    }
                }
            } finally { relationships.close(); }
            Map<String,String> sheets = new LinkedHashMap<>();
            XMLStreamReader workbook = xml(entries, "xl/workbook.xml");
            try {
                while (workbook.hasNext()) {
                    if (workbook.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(workbook.getLocalName())) {
                        String target = targets.get(attribute(workbook, "id"));
                        if (target != null && target.startsWith("xl/worksheets/")) sheets.put(attribute(workbook, "name"), target);
                    }
                }
            } finally { workbook.close(); }
            if (sheets.isEmpty()) throw invalid("XLSX 文件中没有可读取的工作表");
            String selected = sheet.isEmpty() ? sheets.keySet().iterator().next() : sheet;
            if (!sheets.containsKey(selected)) throw invalid("所选工作表不存在");
            List<String> strings = new ArrayList<>();
            if (entries.containsKey("xl/sharedStrings.xml")) {
                XMLStreamReader shared = xml(entries, "xl/sharedStrings.xml");
                try {
                    while (shared.hasNext()) {
                        if (shared.next() == XMLStreamConstants.START_ELEMENT && "si".equals(shared.getLocalName())) {
                            strings.add(xmlText(shared, "si", "t"));
                        }
                    }
                } finally { shared.close(); }
            }
            XMLStreamReader worksheet = xml(entries, sheets.get(selected));
            try {
                return new BatchInputFile(name, data, null, selected, new ArrayList<>(sheets.keySet()), sheetRows(worksheet, strings));
            } finally { worksheet.close(); }
        } catch (IOException | XMLStreamException | IllegalArgumentException | IndexOutOfBoundsException e) {
            throw invalid("XLSX 文件无法读取，请检查文件格式和工作表内容");
        }
    }

    private static String decode(byte[] data, String encoding) {
        try {
            return Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString().replaceFirst("^\uFEFF", "");
        } catch (CharacterCodingException e) {
            throw invalid("CSV 文件无法按 " + encoding + " 解码，请选择正确的编码");
        }
    }

    private static List<Row> csv(String content) {
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false;
        int line = 1, start = 1;
        for (int offset = 0; offset <= content.length(); offset++) {
            boolean end = offset == content.length();
            char ch = end ? '\n' : content.charAt(offset);
            if (ch == '\r') {
                ch = '\n';
                if (offset + 1 < content.length() && content.charAt(offset + 1) == '\n') offset++;
            }
            if (quoted) {
                if (end) throw invalid("CSV 第 " + start + " 行有未闭合的引号");
                if (ch == '"') {
                    if (offset + 1 < content.length() && content.charAt(offset + 1) == '"') {
                        cell.append('"');
                        offset++;
                    } else { quoted = false; closed = true; }
                } else {
                    cell.append(ch);
                    if (ch == '\n') line++;
                }
            } else if (ch == ',' || ch == '\n') {
                cells.add(cell.toString());
                cell.setLength(0);
                closed = false;
                if (cells.size() > MAX_COLUMNS) throw invalid("文件列数超过 " + MAX_COLUMNS);
                if (ch == '\n') {
                    add(rows, new Row(start, cells, null));
                    cells = new ArrayList<>();
                    start = ++line;
                }
            } else if (ch == '"' && cell.length() == 0 && !closed) {
                quoted = true;
            } else {
                if (closed || ch == '"') throw invalid("CSV 第 " + start + " 行的引号格式有误");
                cell.append(ch);
            }
        }
        return rows;
    }

    private static List<Row> sheetRows(XMLStreamReader sheet, List<String> shared) throws XMLStreamException {
        List<Row> rows = new ArrayList<>();
        List<String> cells = null;
        int number = 0;
        String error = null;
        while (sheet.hasNext()) {
            int event = sheet.next();
            if (event == XMLStreamConstants.START_ELEMENT && "row".equals(sheet.getLocalName())) {
                String index = attribute(sheet, "r");
                int next = index.isEmpty() ? number + 1 : Integer.parseInt(index);
                if (next <= number) throw invalid("工作表行号无效");
                number = next;
                cells = new ArrayList<>();
                error = null;
            } else if (event == XMLStreamConstants.START_ELEMENT && "c".equals(sheet.getLocalName()) && cells != null) {
                String type = attribute(sheet, "t");
                String address = attribute(sheet, "r");
                int column = address.isEmpty() ? cells.size() : column(address);
                String value = xmlText(sheet, "c", "inlineStr".equals(type) ? "t" : "v");
                if (column >= MAX_COLUMNS) {
                    if (!value.isEmpty()) throw invalid("文件列数超过 " + MAX_COLUMNS);
                    continue;
                }
                if ("s".equals(type) && !value.isEmpty()) value = shared.get(Integer.parseInt(value));
                if ("e".equals(type)) error = "表格包含错误值：" + value;
                while (cells.size() <= column) cells.add("");
                cells.set(column, value);
            } else if (event == XMLStreamConstants.END_ELEMENT && "row".equals(sheet.getLocalName()) && cells != null) {
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) cells.remove(cells.size() - 1);
                add(rows, new Row(number, cells, error));
                cells = null;
            }
        }
        return rows;
    }

    private static int column(String address) {
        int index = 0;
        for (int i = 0; i < address.length(); i++) {
            char ch = address.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            index = index * 26 + ch - 'A' + 1;
            if (index > MAX_COLUMNS) return MAX_COLUMNS;
        }
        if (index == 0) throw invalid("工作表列坐标无效");
        return index - 1;
    }

    private static void add(List<Row> rows, Row row) {
        if (row.error == null && row.cells.stream().allMatch(value -> value.trim().isEmpty())) return;
        if (rows.size() >= MAX_ROWS + 1) throw invalid("每次最多导入 " + MAX_ROWS + " 篇论文");
        rows.add(row);
    }

    private static Map<String,byte[]> workbookEntries(byte[] data) throws IOException {
        Map<String,byte[]> entries = new HashMap<>();
        int expanded = 0, count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++count > 2048) throw invalid("XLSX 文件结构超出限制");
                String name = entry.getName();
                boolean keep = name.equals("xl/workbook.xml") || name.equals("xl/_rels/workbook.xml.rels")
                    || name.equals("xl/sharedStrings.xml") || name.startsWith("xl/worksheets/") && name.endsWith(".xml");
                ByteArrayOutputStream output = keep ? new ByteArrayOutputStream() : null;
                byte[] buffer = new byte[8192];
                for (int size; (size = zip.read(buffer)) != -1; ) {
                    expanded += size;
                    if (expanded > MAX_EXPANDED) throw invalid("XLSX 解压内容超出限制");
                    if (output != null) output.write(buffer, 0, size);
                }
                if (output != null && entries.putIfAbsent(name, output.toByteArray()) != null) throw invalid("XLSX 内部文件重复");
            }
        }
        return entries;
    }

    private static XMLStreamReader xml(Map<String,byte[]> entries, String name) throws XMLStreamException {
        byte[] bytes = entries.get(name);
        if (bytes == null) throw invalid("XLSX 缺少工作簿或工作表数据");
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) return reader.getAttributeValue(i);
        }
        return "";
    }

    private static String xmlText(XMLStreamReader reader, String end, String valueTag) throws XMLStreamException {
        StringBuilder value = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT && end.equals(reader.getLocalName())) break;
            if (event == XMLStreamConstants.START_ELEMENT && valueTag.equals(reader.getLocalName())) value.append(reader.getElementText());
        }
        return value.toString();
    }

    static final class Row {
        final int number;
        final List<String> cells;
        final String error;
        Row(int number, List<String> cells, String error) { this.number = number; this.cells = cells; this.error = error; }
        String value(int index) { return index < 0 || index >= cells.size() ? "" : cells.get(index); }
    }
}
