package com.riemann.agent.application;

import com.riemann.agent.domain.ConversationId;
import com.riemann.agent.domain.ShareMessage;
import com.riemann.agent.domain.ShareRepository;
import com.riemann.agent.domain.SharedConversation;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 分享用例：把一次已有对话固化为公开只读的分享快照。
 * 复用 ConversationAppService.getConversation 取出完整消息，再映射为快照。
 */
@Service
public class ShareAppService {

    private final ConversationAppService conversationAppService;
    private final ShareRepository shareRepository;

    public ShareAppService(ConversationAppService conversationAppService,
                           ShareRepository shareRepository) {
        this.conversationAppService = conversationAppService;
        this.shareRepository = shareRepository;
    }

    public Mono<String> createShare(String userId, String conversationId) {
        return conversationAppService.getConversation(new ConversationId(conversationId))
                .flatMap(detail -> {
                    String title = detail.messages().stream()
                            .filter(m -> "user".equals(m.type()))
                            .map(ConversationAppService.MessageDto::content)
                            .filter(c -> c != null && !c.isBlank())
                            .findFirst()
                            .orElse("(对话)");
                    List<ShareMessage> messages = detail.messages().stream()
                            .map(m -> new ShareMessage(m.type(), m.content(), m.toolName(), m.arguments()))
                            .toList();
                    String shareId = UUID.randomUUID().toString().replace("-", "");
                    SharedConversation share = new SharedConversation(
                            shareId, title, Instant.now().toEpochMilli(), messages);
                    return shareRepository.save(share).thenReturn(shareId);
                });
    }

    public Mono<SharedConversation> getShare(String shareId) {
        return shareRepository.findById(shareId);
    }
}
