package com.qualityos.common.attachment;

/** Lets whatever limits an organisation's files refuse an upload, without attachments knowing why. */
public interface AttachmentQuota {

    void requireAttachmentPlace(long bytes);
}
