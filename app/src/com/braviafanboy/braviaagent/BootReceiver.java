package com.braviafanboy.braviaagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the agent after the TV boots. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AgentService.start(context);
        }
    }
}
