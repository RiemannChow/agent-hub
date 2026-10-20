package com.riemann.agent.infrastructure.seekdb.books;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图书数据导入器，对应 Python 版 import_data.py。
 *
 * <p>在 seekdb 环境下随应用启动运行一次：若 demo_books.book_info 为空，则从
 * bestsellers_with_categories.csv 读取 550 条 Amazon 畅销书记录，计算离线向量嵌入后批量写入。</p>
 *
 * <p>与 Python 版 import_data.py 的对应关系：
 * <ul>
 *   <li>load_data / DataProcessor.prepare_data -> {@link CsvBookLoader}（含 RFC4180 解析）</li>
 *   <li>DefaultEmbeddingFunction -> {@link BookEmbedder}（复用 LocalEmbedder）</li>
 *   <li>collection.add(batch) -> {@link BookRepository#batchInsert}</li>
 *   <li>create_metadata_indexes -> 建表时已建好标量索引（见 02_books.sql）</li>
 * </ul>
 * </p>
 */
@Component
@Profile("seekdb")
@Order(1)
public class BookDataImporter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BookDataImporter.class);

    private final BookRepository repository;

    @Value("${agent.books.csv-path:sql/bestsellers_with_categories.csv}")
    private String csvPath;

    public BookDataImporter(BookRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) throws Exception {
        repository.ensureTable();
        int existing = repository.count();
        if (existing > 0) {
            log.info("[BookDataImporter] demo_books.book_info 已存在 {} 条，跳过导入", existing);
            return;
        }
        List<Book> books = CsvBookLoader.loadFromPath(csvPath);
        repository.batchInsert(books);
        log.info("[BookDataImporter] 已导入 {} 条 Amazon 畅销书（含离线向量嵌入）到 demo_books.book_info",
                books.size());
    }
}
