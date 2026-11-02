package com.braviafanboy.braviaagent;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can grant Notification access, which is what
 * MediaSessionManager requires before it will list other apps' sessions.
 * It never reads notifications.
 */
public final class MediaListenerService extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        AgentService s = AgentService.instance();
        if (s != null) s.onPermissionsMaybeChanged();
    }

    @Override
    public void onListenerDisconnected() {
        AgentService s = AgentService.instance();
        if (s != null) s.onPermissionsMaybeChanged();
    }
}
