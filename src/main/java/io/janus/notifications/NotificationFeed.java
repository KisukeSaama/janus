package io.janus.notifications;

import java.util.List;

/** The announcements and their unread count, so the console can badge the menu in one request. */
public record NotificationFeed(List<NotificationResponse> items, long unread) {}
