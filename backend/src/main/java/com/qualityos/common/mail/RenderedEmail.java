package com.qualityos.common.mail;

/** Bodies can hold one-time links, so this must never be logged. */
public record RenderedEmail(String subject, String html, String text) {

    @Override
    public String toString() {
        return "RenderedEmail[" + subject + "]";
    }
}
