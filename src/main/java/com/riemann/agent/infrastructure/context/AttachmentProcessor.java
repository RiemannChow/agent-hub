package com.riemann.agent.infrastructure.context;

import com.riemann.agent.presentation.ChatAttachment;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 把前端上传的附件转换成 AgentScope 的 {@link ContentBlock}。
 *
 * <p>图片以 base64 构造 {@link ImageBlock}，模型能真正「看到」；文本与 PDF 抽取出的文字拼成 {@link TextBlock}。所有附件都经过数量、大小与
 * MIME 白名单校验，并受 {@link AttachmentProperties} 约束； 非法附件被跳过并给出明确提示——上传失败绝不能让整轮对话失败。
 */
@Component
public class AttachmentProcessor {

    private static final Logger log = LoggerFactory.getLogger(AttachmentProcessor.class);

    public static final String ATTR_KEY = "attachments";

    private static final String PDF_TYPE = "application/pdf";
    private static final Set<String> IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final Set<String> TEXT_TYPES =
            Set.of(
                    "text/plain",
                    "text/csv",
                    "text/markdown",
                    "application/json",
                    "application/x-ndjson",
                    "application/xml",
                    "text/xml");

    public record Processed(List<ContentBlock> blocks, String note) {}

    private final AttachmentProperties props;

    public AttachmentProcessor() {
        this(new AttachmentProperties());
    }

    @Autowired
    public AttachmentProcessor(AttachmentProperties props) {
        this.props = props;
    }

    /** 从请求级属性里取出附件（只有 UI 的 chat 端点会写入；A2A 走纯文本）。 */
    @SuppressWarnings("unchecked")
    public static List<ChatAttachment> fromAttributes(Map<String, Object> attributes) {
        if (attributes == null || !(attributes.get(ATTR_KEY) instanceof List<?> raw)) {
            return List.of();
        }
        List<ChatAttachment> result = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof ChatAttachment a) {
                result.add(a);
            }
        }
        return result;
    }

    public Processed process(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new Processed(List.of(), "");
        }
        List<ContentBlock> blocks = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int count = 0;
        int totalBytes = 0;

        for (ChatAttachment attachment : attachments) {
            if (count >= props.getMaxFiles()) {
                notes.add("附件数量超过 " + props.getMaxFiles() + " 个，其余已忽略");
                break;
            }
            if (attachment == null || attachment.data() == null || attachment.data().isBlank()) {
                notes.add("存在空附件，已忽略");
                continue;
            }
            String mime = normalize(attachment.mimeType());
            boolean image = IMAGE_TYPES.contains(mime);
            boolean pdf = PDF_TYPE.equals(mime) && props.isPdfEnabled();
            boolean text = TEXT_TYPES.contains(mime);
            if (!image && !text && !pdf) {
                notes.add("不支持的附件类型：" + mime + "（" + safeName(attachment.name()) + "）");
                continue;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(stripDataUrl(attachment.data()));
            } catch (IllegalArgumentException e) {
                notes.add("附件不是合法 base64：" + safeName(attachment.name()));
                continue;
            }
            if (bytes.length == 0 || bytes.length > props.getMaxSingleBytes()) {
                notes.add(
                        "附件为空或超过 "
                                + (props.getMaxSingleBytes() / 1024 / 1024)
                                + "MB："
                                + safeName(attachment.name()));
                continue;
            }
            if (totalBytes + bytes.length > props.getMaxTotalBytes()) {
                notes.add("附件总大小超过 " + (props.getMaxTotalBytes() / 1024 / 1024) + "MB，其余已忽略");
                break;
            }

            String extractedText = null;
            if (pdf) {
                extractedText = extractPdfText(bytes);
                if (extractedText == null) {
                    notes.add("PDF 解析失败：" + safeName(attachment.name()));
                    continue;
                }
            }
            totalBytes += bytes.length;
            count++;

            if (image) {
                blocks.add(
                        ImageBlock.builder()
                                .source(
                                        new Base64Source(
                                                mime, Base64.getEncoder().encodeToString(bytes)))
                                .build());
                notes.add("图片：" + safeName(attachment.name()));
            } else {
                String raw = pdf ? extractedText : new String(bytes, StandardCharsets.UTF_8);
                if (raw.length() > props.getMaxTextChars()) {
                    raw = raw.substring(0, props.getMaxTextChars()) + "\n…（附件过长已截断）";
                }
                String label = pdf ? "PDF 文本" : "文本";
                blocks.add(
                        TextBlock.builder()
                                .text(
                                        "【附件："
                                                + safeName(attachment.name())
                                                + "（"
                                                + label
                                                + "）】\n"
                                                + raw)
                                .build());
                notes.add(label + "：" + safeName(attachment.name()));
            }
        }
        String note = notes.isEmpty() ? "" : "【附件处理】" + String.join("；", notes);
        if (!blocks.isEmpty()) {
            log.debug(
                    "[Attachments] accepted {} block(s), totalBytes={}", blocks.size(), totalBytes);
        }
        return new Processed(List.copyOf(blocks), note);
    }

    /** Extracts a bounded amount of text from a PDF; {@code null} means parsing failed. */
    private String extractPdfText(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pages = Math.min(document.getNumberOfPages(), Math.max(1, props.getMaxPdfPages()));
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            return stripper.getText(document);
        } catch (Exception e) {
            log.warn("[Attachments] PDF extraction failed: {}", e.toString());
            return null;
        }
    }

    private static String normalize(String mime) {
        return mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripDataUrl(String data) {
        int comma = data.indexOf(',');
        return comma > 0 && data.regionMatches(true, 0, "data:", 0, 5)
                ? data.substring(comma + 1)
                : data;
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        return name.replaceAll("[\\r\\n<>|]", "_").substring(0, Math.min(80, name.length()));
    }
}
