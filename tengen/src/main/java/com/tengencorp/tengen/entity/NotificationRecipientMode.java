package com.tengencorp.tengen.entity;

/** How an email or SMS rule resolves its recipient at event time. */
public enum NotificationRecipientMode {
    FIXED,
    EVENT_FIELD
}
