package com.braviafanboy.braviaagent.core;

/**
 * Which requests are answered without the bearer token. Everything else needs
 * it, whether or not the agent is paired: an agent nobody has paired with yet
 * answers nothing more than this.
 */
public final class Access {
    private Access() {
    }

    /**
     * What a client needs before it can hold a token: who it is talking to
     * ({@code /v1/info}, also served at {@code /}), and pairing itself.
     */
    public static boolean isPublic(String path) {
        return "/v1/info".equals(path) || "/".equals(path) || "/v1/pair".equals(path);
    }
}
