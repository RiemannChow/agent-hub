package com.riemann.agent.infrastructure.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riemann.agent.domain.ShareRepository;
import com.riemann.agent.domain.SharedConversation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件落地实现的分享快照仓储。
 * 每个分享快照写成一份 JSON 文件（{shareId}.json），默认目录 ./data/shares。
 * 不依赖任何外部数据库，随应用进程生命周期存在，重启后依然可读。
 */
@Repository
public class FileShareRepository implements ShareRepository {

    private final Path dir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FileShareRepository(@Value("${agent.share.dir:./data/shares}") String dirPath) {
        this.dir = Path.of(dirPath);
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建分享目录: " + this.dir, e);
        }
    }

    @Override
    public Mono<Void> save(SharedConversation share) {
        return Mono.fromCallable(() -> {
            Path file = dir.resolve(share.shareId() + ".json");
            objectMapper.writeValue(file.toFile(), share);
            return (Void) null;
        }).then();
    }

    @Override
    public Mono<SharedConversation> findById(String shareId) {
        return Mono.fromCallable(() -> {
            Path file = dir.resolve(shareId + ".json");
            if (!Files.exists(file)) {
                return null;
            }
            return objectMapper.readValue(file.toFile(), SharedConversation.class);
        });
    }
}
