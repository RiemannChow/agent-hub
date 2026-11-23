package com.riemann.agent.infrastructure.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.presentation.ChatAttachment;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

    @Test
    void pdfTextIsExtractedIntoATextBlock() throws IOException {
        byte[] pdf = tinyPdf("Q3 revenue 2024");

        AttachmentProcessor.Processed result =
                processor.process(
                        List.of(new ChatAttachment("quarterly.pdf", "application/pdf", b64(pdf))));

        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) result.blocks().get(0)).getText())
                .contains("Q3 revenue 2024")
                .contains("PDF 文本");
        assertThat(result.note()).contains("PDF 文本：quarterly.pdf");
    }

    @Test
    void disabledPdfIsSkippedWithExplanation() throws IOException {
        AttachmentProperties props = new AttachmentProperties();
        props.setPdfEnabled(false);
        AttachmentProcessor disabled = new AttachmentProcessor(props);

        AttachmentProcessor.Processed result =
                disabled.process(
                        List.of(
                                new ChatAttachment(
                                        "report.pdf", "application/pdf", b64(tinyPdf("secret")))));

        assertThat(result.blocks()).isEmpty();
        assertThat(result.note()).contains("不支持的附件类型");
    }

    @Test
    void brokenPdfIsSkippedWithoutFailingTheTurn() {
        byte[] notAPdf = "%PDF-1.7 broken".getBytes(StandardCharsets.UTF_8);

        AttachmentProcessor.Processed result =
                processor.process(
                        List.of(new ChatAttachment("broken.pdf", "application/pdf", b64(notAPdf))));

        assertThat(result.blocks()).isEmpty();
        assertThat(result.note()).contains("PDF 解析失败");
    }

    /** Builds a one-page PDF containing the given line (PDFBox 3 API). */
    private static byte[] tinyPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
