package com.riemann.agent.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.presentation.ChatAttachment;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttachmentProcessorTest {

    private final AttachmentProcessor processor = new AttachmentProcessor();

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void imageBecomesAnImageBlock() {
        AttachmentProcessor.Processed result =
                processor.process(
                        List.of(
                                new ChatAttachment(
                                        "chart.png", "image/png", b64("fake".getBytes()))));

        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0)).isInstanceOf(ImageBlock.class);
        assertThat(result.note()).contains("chart.png");
    }

    @Test
    void dataUrlPrefixIsAccepted() {
        AttachmentProcessor.Processed result =
                processor.process(
                        List.of(
                                new ChatAttachment(
                                        "a.png",
                                        "image/png",
                                        "data:image/png;base64," + b64("fake".getBytes()))));

        assertThat(result.blocks()).hasSize(1);
    }

    @Test
    void textAttachmentIsInlinedAndTruncated() {
        String big = "sales=100\n".repeat(1000);
        AttachmentProcessor.Processed result =
                processor.process(
                        List.of(
                                new ChatAttachment(
                                        "report.csv",
                                        "text/csv",
                                        b64(big.getBytes(StandardCharsets.UTF_8)))));

        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) result.blocks().get(0)).getText()).contains("已截断");
    }

    @Test
    void unsupportedMimeIsSkippedWithExplanation() {
        AttachmentProcessor.Processed result =
                processor.process(
                        List.of(
                                new ChatAttachment(
                                        "virus.exe",
                                        "application/x-msdownload",
                                        b64("x".getBytes()))));

        assertThat(result.blocks()).isEmpty();
        assertThat(result.note()).contains("不支持的附件类型");
    }

    @Test
    void oversizedFileIsSkipped() {
        byte[] tooBig = new byte[2 * 1024 * 1024 + 1];
        AttachmentProcessor.Processed result =
                processor.process(List.of(new ChatAttachment("big.png", "image/png", b64(tooBig))));

        assertThat(result.blocks()).isEmpty();
        assertThat(result.note()).contains("超过");
    }

    @Test
    void emptyInputProducesNoBlocks() {
        assertThat(processor.process(null).blocks()).isEmpty();
        assertThat(processor.process(List.of()).blocks()).isEmpty();
    }
}
