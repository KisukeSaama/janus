package io.janus.notifications;

import java.util.UUID;

import org.springframework.web.bind.annotation.*;

/** HTTP surface for expiry announcements. Every decision belongs to {@link NotificationService}. */
@RestController
@RequestMapping("/api/admin/notifications")
public class NotificationAdminController {
    private final NotificationService notifications;

    public NotificationAdminController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationFeed list(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return notifications.list(unreadOnly);
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable UUID id) {
        return notifications.markRead(id);
    }

    @PostMapping("/read")
    public NotificationFeed markAllRead() {
        return notifications.markAllRead();
    }

    @DeleteMapping("/{id}")
    public void dismiss(@PathVariable UUID id) {
        notifications.dismiss(id);
    }
}
