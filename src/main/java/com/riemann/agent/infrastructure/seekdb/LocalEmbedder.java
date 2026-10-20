package com.riemann.agent.infrastructure.seekdb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地确定性向量嵌入器（离线、无外部依赖）。
 *
 * <p>将文本中的 token（CJK 单字 + 英文/数字词）哈希到固定维度并 L2 归一化。
 * 语义相近的文本会得到相近的向量，因此可在 SeekDB 中用 l2_distance 做近邻检索，
 * 从而“验证向量查询可用”。</p>
 *
 * <p>注意：该嵌入仅用于本项目“可验证向量检索”演示，并非语义大模型 embedding；
 * 生产环境应替换为真实 embedding 服务（如腾讯混元 embedding / OpenAI text-embedding 等）。</p>
 */
public final class LocalEmbedder {

    public static final int DIM = 256;

    private LocalEmbedder() {
    }

    /**
     * 文本 -> 归一化后的 DIM 维词频向量（非负）。
     * 采用“词频计数 + L2 归一化”：语义相近的文本在更多维度上共有非零分量，
     * 因而 l2_distance 更小、可被向量近邻检索命中。避免符号翻转，否则共有词会被抵消。
     */
    public static double[] embed(String text) {
        double[] vec = new double[DIM];
        if (text == null || text.isBlank()) {
            return vec;
        }
        List<String> tokens = tokenize(text.toLowerCase());
        for (String t : tokens) {
            int idx = (int) Long.remainderUnsigned(hash(t), DIM);
            vec[idx] += 1.0; // 词频计数（非负）
        }
        // L2 归一化，使向量长度一致、距离只反映“方向（词分布）”相似度
        double norm = 0;
        for (double v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        }
        return vec;
    }

    /** 文本 -> SeekDB 接受的向量字符串，如 "[0.12,-0.34,...]"。 */
    public static String toSql(double[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(round(vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    public static String embedToSql(String text) {
        return toSql(embed(text));
    }

    // ===================== 内部辅助 =====================

    private static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                buf.append(c);
            } else {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
            }
        }
        if (buf.length() > 0) tokens.add(buf.toString());
        return tokens;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static long hash(String t) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(t.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (d[i] & 0xFFL);
            }
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
