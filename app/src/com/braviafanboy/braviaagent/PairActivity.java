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
        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        android.util.Log.i("BraviaAgent", "pair link from " + getReferrer());
        if (data != null && "braviaagent".equals(data.getScheme()) && "pair".equals(data.getHost())) {
            String token = data.getQueryParameter("token");
            String name = data.getQueryParameter("name");
            AgentService svc = AgentService.instance();
            if (token != null && svc != null) {
                svc.pairing().adopt(token, name == null ? "deep_link" : name);
            } else if (token != null) {
                // Service not up yet: let it adopt once created.
                Pairing p = new Pairing(this);
                p.adopt(token, name == null ? "deep_link" : name);
            }
        }
        finish();
    }
}
