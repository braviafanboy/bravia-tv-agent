package com.braviafanboy.braviaagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the agent after the TV boots or the app is reinstalled (both are
 *  foreground-service exemptions, so the service can be promoted). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            AgentService.start(context);
        }
    }
}
