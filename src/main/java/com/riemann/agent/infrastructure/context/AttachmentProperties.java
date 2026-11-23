package com.riemann.agent.infrastructure.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounds for user-supplied chat attachments ({@code agent.attachments.*}). */
@Component
@ConfigurationProperties(prefix = "agent.attachments")
public class AttachmentProperties {

    /** Maximum number of accepted attachments per turn. */
    private int maxFiles = 5;

    /** Maximum decoded size of a single attachment. */
    private int maxSingleBytes = 2 * 1024 * 1024;

    /** Maximum decoded size of all accepted attachments in one turn. */
    private int maxTotalBytes = 6 * 1024 * 1024;

    /** Maximum characters of extracted text kept per attachment. */
    private int maxTextChars = 6000;

    /** Maximum PDF pages scanned per attachment. */
    private int maxPdfPages = 10;

    /** When false, PDF attachments are skipped with an explanation. */
    private boolean pdfEnabled = true;

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public int getMaxSingleBytes() {
        return maxSingleBytes;
    }

    public void setMaxSingleBytes(int maxSingleBytes) {
        this.maxSingleBytes = maxSingleBytes;
    }

    public int getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(int maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }

    public int getMaxPdfPages() {
        return maxPdfPages;
    }

    public void setMaxPdfPages(int maxPdfPages) {
        this.maxPdfPages = maxPdfPages;
    }

    public boolean isPdfEnabled() {
        return pdfEnabled;
    }

    public void setPdfEnabled(boolean pdfEnabled) {
        this.pdfEnabled = pdfEnabled;
    }
}
