package com.riemann.agent.infrastructure.seekdb.books;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 CSV 加载图书数据，对应 Python 版 import_data.load_data + data/processor.py。
 *
 * <p>数据集 bestsellers_with_categories.csv 的标题列含有逗号并被双引号包裹
 * （如 "5,000 Awesome Facts (About Everything!)"），因此这里实现了一个最小化的
 * RFC4180 解析器（支持引号包裹字段、字段内逗号、以及 "" 转义引号），而非简单按逗号切分。</p>
 */
public final class CsvBookLoader {

    private static final String HEADER = "Name,Author,User Rating,Reviews,Price,Year,Genre";

    private CsvBookLoader() {
    }

    /**
     * 按行解析 CSV（首行为表头，需为 {@link #HEADER}）。
     *
     * @return 解析后的图书列表（document = name + " " + author）
     */
    public static List<Book> load(Reader reader) throws IOException {
        List<Book> books = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String header = br.readLine();
            if (header == null) {
                throw new IOException("CSV 为空，缺少表头");
            }
            String line;
            int idx = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> f = parseLine(line);
                if (f.size() < 7) {
                    throw new IOException("行 " + (idx + 1) + " 字段数不足: " + line);
                }
                String name = f.get(0).trim();
                String author = f.get(1).trim();
                double userRating = parseDouble(f.get(2), "User Rating", idx);
                int reviews = parseInt(f.get(3), "Reviews", idx);
                double price = parseDouble(f.get(4), "Price", idx);
                int year = parseInt(f.get(5), "Year", idx);
                String genre = f.get(6).trim();
                String document = (name + " " + author).trim();
                books.add(new Book(String.valueOf(idx), name, author, userRating, reviews,
                        price, year, genre, document, null));
                idx++;
            }
        }
        return books;
    }

    /** 优先按文件系统路径加载，其次回退到 classpath 资源。 */
    public static List<Book> loadFromPath(String csvPath) throws IOException {
        File file = new File(csvPath);
        if (file.exists()) {
            try (FileReader fr = new FileReader(file, StandardCharsets.UTF_8)) {
                return load(fr);
            }
        }
        ClassPathResource res = new ClassPathResource(csvPath);
        if (res.exists()) {
            try (InputStream in = res.getInputStream();
                 InputStreamReader isr = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return load(isr);
            }
        }
        throw new IOException("找不到 CSV 文件: " + csvPath
                + "（已尝试文件系统路径与 classpath）");
    }

    // ===================== 内部辅助 =====================

    /** 单行 RFC4180 解析：处理引号包裹字段与字段内逗号。 */
    private static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    private static double parseDouble(String s, String field, int idx) throws IOException {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IOException("行 " + (idx + 1) + " 的 " + field + " 无法解析为数字: '" + s + "'");
        }
    }

    private static int parseInt(String s, String field, int idx) throws IOException {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IOException("行 " + (idx + 1) + " 的 " + field + " 无法解析为整数: '" + s + "'");
        }
    }
}
