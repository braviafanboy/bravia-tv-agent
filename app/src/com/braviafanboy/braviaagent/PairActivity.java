package com.braviafanboy.braviaagent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Invisible target of the cloud deep link {@code braviaagent://pair?token=…}.
 * Adopts the token and finishes at once, so token delivery does not pull the
 * status screen over whatever the user is watching.
 */
public final class PairActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AgentService.start(this);
        android.util.Log.i("BraviaAgent", "pair link from " + getReferrer());
        adoptPairLink(this, getIntent());
        finish();
    }

    /** Adopt the token of a {@code braviaagent://pair?token=…} intent; true if it was one. */
    static boolean adoptPairLink(android.content.Context context, Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"braviaagent".equals(data.getScheme()) || !"pair".equals(data.getHost())) return false;
        String token = data.getQueryParameter("token");
        String name = data.getQueryParameter("name");
        if (token == null) return true;
        AgentService svc = AgentService.instance();
        if (svc != null) {
            svc.pairing().adopt(token, name == null ? "deep_link" : name);
        } else {
            // Service not up yet: it reads the stored token when it starts.
            new Pairing(context).adopt(token, name == null ? "deep_link" : name);
        }
        return true;
    }
}
