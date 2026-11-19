package com.braviafanboy.braviaagent.core;

import java.security.MessageDigest;
import java.util.Random;

/**
 * Tests for the pairing rules, run on a desktop JVM by app/test.sh: who gets a
 * token, and who does not.
 */
public final class PairingTest {
    private static int passed;

    /** A clock the test moves, and storage that survives a "restart". */
    private static final class World implements PairingState.Clock, PairingState.Storage {
        long now = 1_000_000;
        PairingState.Paired stored;
        int saves;

        @Override public long elapsedMs() {
            return now;
        }

        @Override public long wallMs() {
            return 1_700_000_000_000L + now;
        }

        @Override public PairingState.Paired load() {
            return stored;
        }

        @Override public void save(PairingState.Paired paired) {
            stored = paired;
            saves++;
        }

        @Override public void clear() {
            stored = null;
        }

        PairingState start() {
            PairingState p = new PairingState(this, this, new Random(saves + 7));
            p.setCertificate(CERT);
            return p;
        }
    }

    private static final String HA = "192.0.2.10";
    private static final String OTHER = "192.0.2.66";
    private static final String THIRD = "192.0.2.67";
    /** The agent's certificate SHA-256, and another one. */
    private static final String CERT = hex(run(64));
    private static final String OTHER_CERT = hex(run(96));
    /** A client secret, and the commitment to it. */
    private static final String SECRET = hex(run(0));
    private static final String COMMIT = hex(sha256(run(0)));

    public static void main(String[] args) {
        unpaired();
        codes();
        pairing();
        commitAndReveal();
        anotherClient();
        somethingInBetween();
        takingTurns();
        aRequestNobodyAnswers();
        deadlines();
        repairing();
        names();
        System.out.println("ok: " + passed + " pairing checks passed");
    }

    /** An agent nobody has paired with authorises nothing. */
    private static void unpaired() {
        PairingState p = new World().start();
        check(!p.isPaired(), "starts unpaired");
        check(!p.authorized(null), "no credentials are not credentials");
        check(!p.authorized(""), "empty header");
        check(!p.authorized("Bearer "), "empty token");
        check(!p.authorized("Bearer 0000"), "any token");
        check(p.pending() == null, "nothing is waiting");
        check(p.accept("0123456789abcdef0123456789abcdef") == null, "nothing to allow");
        check(p.deny(null) == null, "nothing to deny");
        check(!p.shown("0123456789abcdef0123456789abcdef"), "nothing to show");
        eq(403, p.collect("0123456789abcdef0123456789abcdef").status, "nothing to collect");
        eq(403, p.collect(null).status, "no request id");
        eq(403, p.reveal("0123456789abcdef0123456789abcdef", SECRET).status, "nothing to reveal to");
    }

    /**
     * The code, on fixed values. The integration's tests hold the same three
     * (tests/test_agent.py), so the two sides cannot drift apart.
     */
    private static void codes() {
        eq("576869", PairingState.code(run(0), run(32), run(64)), "the code for known values");
        eq("685108", PairingState.code(new byte[32], new byte[32], new byte[32]), "for zeros");
        eq("023151", PairingState.code(fill(1), fill(0), fill(0)), "a leading zero is kept");
        check(!PairingState.code(run(0), run(32), run(64)).equals(PairingState.code(run(0), run(32), run(96))),
            "another certificate, another code");
        check(!PairingState.code(run(0), run(32), run(64)).equals(PairingState.code(run(1), run(32), run(64))),
            "another client secret, another code");
        check(!PairingState.code(run(0), run(32), run(64)).equals(PairingState.code(run(0), run(33), run(64))),
            "another agent secret, another code");
    }

    /** Ask, reveal, be allowed by the viewer, collect. */
    private static void pairing() {
        World w = new World();
        PairingState p = w.start();
        PairingState.Reply asked = p.request("Home Assistant", HA, COMMIT);
        eq(202, asked.status, "a request is accepted");
        check(asked.request != null && asked.request.matches("[0-9a-f]{32}"), "it gets an id");
        check(asked.nonce != null && asked.nonce.matches("[0-9a-f]{64}"), "and the agent's secret");
        check(!asked.accepted && asked.token == null, "and nothing else");
        eq(30L, asked.seconds, "it waits 30 s");
        check(!p.isPaired() && !p.authorized("Bearer " + asked.request), "the id is not a token");

        check(p.pending().code() == null, "no code before the client has revealed its secret");
        check(!p.shown(asked.request), "so it cannot be put before the viewer");
        check(p.accept(asked.request) == null, "nor allowed");
        eq(202, p.collect(asked.request).status, "collecting meanwhile: not yet");

        PairingState.Reply revealed = p.reveal(asked.request, SECRET);
        eq(202, revealed.status, "the secret matches the commitment");
        check(revealed.nonce == null && revealed.token == null, "the answer carries nothing new");
        String code = PairingState.code(unhex(SECRET), unhex(asked.nonce), unhex(CERT));
        eq(code, p.pending().code(), "the viewer's code is the one the client computes for itself");
        eq(202, p.reveal(asked.request, SECRET).status, "revealing again changes nothing");
        eq(code, p.pending().code(), "the code stays");

        PairingState.Reply early = p.collect(asked.request);
        eq(202, early.status, "collecting before the viewer answered: not yet");
        check(!early.accepted && early.token == null, "no token before that");
        check(p.shown(asked.request), "it is on screen");
        check(p.accept("00000000000000000000000000000000") == null, "allowing another id allows nothing");

        PairingState.Request allowed = p.accept(asked.request);
        check(allowed != null && allowed.accepted(), "the viewer allows it");
        eq("Home Assistant", allowed.name, "the viewer was told who");
        eq(HA, allowed.peer, "and from where");
        check(!p.isPaired(), "allowed is not yet paired");
        check(p.deny(asked.request) == null, "an allowed request cannot be denied any more");

        PairingState.Reply done = p.collect(asked.request);
        eq(200, done.status, "collected");
        check(done.token != null && done.token.matches("[0-9a-f]{64}"), "a 256-bit token");
        check(p.isPaired(), "paired");
        check(p.authorized("Bearer " + done.token), "the token authorises");
        check(p.authorized("bearer  " + done.token + " "), "scheme case and spaces do not matter");
        check(!p.authorized(done.token), "the token alone is not a header");
        check(!p.authorized("Basic " + done.token), "another scheme");
        check(!p.authorized("Bearer " + done.token.substring(1) + "0"), "a wrong token of the right length");
        check(!p.authorized("Bearer " + done.token + "0"), "a longer one");
        eq("Home Assistant", p.paired().name, "who paired is kept");
        eq(HA, p.paired().peer, "and from where");
        eq(403, p.collect(asked.request).status, "a request is collected once");
        check(p.pending() == null, "nothing is waiting afterwards");

        // After a restart the pairing is still there.
        PairingState again = w.start();
        check(again.isPaired() && again.authorized("Bearer " + done.token), "the pairing survives a restart");
        again.clear();
        check(!again.isPaired() && !again.authorized("Bearer " + done.token) && w.stored == null, "cleared");
    }

    /** The commitment comes first and is held to. */
    private static void commitAndReveal() {
        World w = new World();
        PairingState p = w.start();
        eq(400, p.request("Home Assistant", HA, null).status, "no commitment: an older client");
        eq("commit_required", p.request("Home Assistant", HA, null).error, "told why");
        eq(400, p.request("Home Assistant", HA, "abcd").status, "too short");
        eq(400, p.request("Home Assistant", HA, COMMIT.substring(1) + "g").status, "not hex");
        check(p.pending() == null, "none of these left a request");
        eq(202, p.request("Home Assistant", HA, COMMIT.toUpperCase()).status, "upper case is hex too");
        eq(202, p.reveal(p.pending().id, SECRET).status, "and matches the same secret");
        p.clear();

        // Another secret than the one committed to: the request is over.
        PairingState.Reply asked = p.request("Home Assistant", HA, COMMIT);
        eq(403, p.reveal(asked.request, hex(run(1))).status, "a secret that does not match");
        check(p.pending() == null, "ends the request");
        eq(403, p.reveal(asked.request, SECRET).status, "for good");
        eq(202, p.request("Home Assistant", HA, COMMIT).status, "the client may start over");
        eq(403, p.reveal(p.pending().id, "xyz").status, "a malformed secret ends it too");

        // Without the certificate there is nothing to tie a code to.
        PairingState bare = new PairingState(w, w, new Random(1));
        asked = bare.request("Home Assistant", HA, COMMIT);
        eq(403, bare.reveal(asked.request, SECRET).status, "no certificate, no code");
    }

    /**
     * What a client on the network that is not Home Assistant can do: ask,
     * and be shown to the viewer with a code nobody else is showing.
     */
    private static void anotherClient() {
        // Home Assistant asks first; the other client is turned away while
        // the request waits, and cannot collect it without its id.
        World w = new World();
        PairingState p = w.start();
        PairingState.Reply ha = p.request("Home Assistant", HA, COMMIT);
        PairingState.Reply other = p.request("Home Assistant", OTHER, hex(sha256(run(5))));
        eq(409, other.status, "one request at a time");
        eq("pairing_busy", other.error, "the reason");
        check(other.request == null && other.nonce == null && other.seconds > 0 && other.seconds <= 30,
            "told when to ask again, and nothing else");
        p.reveal(ha.request, SECRET);
        p.shown(ha.request);
        p.accept(ha.request);
        eq(403, p.collect("00000000000000000000000000000000").status, "a guessed id collects nothing");
        eq(403, p.collect(ha.request.substring(1) + "0").status, "nor a near miss");
        check(p.collect(ha.request).token != null, "Home Assistant collects");

        // The other client asks first. Its request reaches the viewer with a
        // code of its own; Home Assistant, turned away, shows none. Denied.
        w = new World();
        p = w.start();
        PairingState.Reply squat = p.request("Home Assistant", OTHER, hex(sha256(run(5))));
        eq(202, squat.status, "the other client holds the request");
        eq(202, p.reveal(squat.request, hex(run(5))).status, "and gives it a code");
        eq(409, p.request("Home Assistant", HA, COMMIT).status, "Home Assistant is told to wait");
        check(p.shown(squat.request), "the screen is opened: the viewer is asked");
        eq(202, p.collect(squat.request).status, "which alone gives the other client nothing");
        PairingState.Request denied = p.deny(squat.request);
        check(denied != null && OTHER.equals(denied.peer), "the viewer denies it");
        PairingState.Reply told = p.collect(squat.request);
        eq(403, told.status, "it is over");
        eq("pairing_denied", told.error, "and its client is told why");
        check(!p.isPaired(), "nobody is paired");
        check(p.accept(squat.request) == null, "a denied request cannot be allowed afterwards");
        // It cannot take the place again at once; Home Assistant gets in.
        PairingState.Reply again = p.request("Home Assistant", OTHER, hex(sha256(run(5))));
        eq(409, again.status, "the same address has to wait before asking again");
        check(again.seconds > 0 && again.seconds <= 60, "for at most a minute");
        ha = p.request("Home Assistant", HA, COMMIT);
        eq(202, ha.status, "Home Assistant's request is accepted meanwhile");
        eq("pairing_closed", p.collect(squat.request).error, "the denial is forgotten once another request is made");
        p.reveal(ha.request, SECRET);
        p.accept(ha.request);
        check(p.collect(ha.request).token != null, "and it pairs");
        w.now += PairingState.COOLDOWN_MS;
        eq(202, p.request("x", OTHER, COMMIT).status, "after the wait the other address may ask again");
    }

    /**
     * Something between the client and the agent that answers the client
     * with a certificate of its own, and passes the pairing on as its own.
     * The client's code comes from the certificate it saw, the viewer's from
     * the agent's: they differ, whatever the thing in between sends.
     */
    private static void somethingInBetween() {
        World w = new World();
        PairingState agent = w.start();
        // It must commit towards the agent before it knows the client's
        // secret (the client reveals only after it got "the agent's" secret).
        byte[] own = run(9);
        PairingState.Reply fromAgent = agent.request("Home Assistant", OTHER, hex(sha256(own)));
        agent.reveal(fromAgent.request, hex(own));
        String onTv = agent.pending().code();
        // The client meanwhile computes with its own secret, the secret it
        // was handed, and the certificate it saw.
        int same = 0;
        for (int handed = 0; handed < 200; handed++) {
            String atClient = PairingState.code(unhex(SECRET), run(handed), unhex(OTHER_CERT));
            if (atClient.equals(onTv)) same++;
        }
        check(same <= 1, "the two codes agree by chance only (" + same + " of 200)");
        // And it cannot change its mind about its secret afterwards.
        eq(202, agent.reveal(fromAgent.request, hex(own)).status, "the same secret again is fine");
        eq(onTv, agent.pending().code(), "and leaves the code alone");
        eq(403, agent.reveal(fromAgent.request, SECRET).status, "another secret ends the request");
    }

    /**
     * Addresses taking turns. Each one whose request lapsed waits out its own
     * minute, whoever asked in between.
     */
    private static void takingTurns() {
        World w = new World();
        PairingState p = w.start();
        eq(202, p.request("x", OTHER, COMMIT).status, "one address asks");
        w.now += PairingState.REQUEST_MS;
        eq(202, p.request("x", THIRD, COMMIT).status, "when that lapses, another does");
        w.now += PairingState.REQUEST_MS;
        PairingState.Reply first = p.request("x", OTHER, COMMIT);
        eq(409, first.status, "the first is still waiting out its minute");
        eq(30L, first.seconds, "half of which is left");
        PairingState.Reply second = p.request("x", THIRD, COMMIT);
        eq(409, second.status, "and the second has just begun its own");
        eq(60L, second.seconds, "all of it");
        PairingState.Reply ha = p.request("Home Assistant", HA, COMMIT);
        eq(202, ha.status, "so Home Assistant gets its turn");
        p.reveal(ha.request, SECRET);
        p.accept(ha.request);
        check(p.collect(ha.request).token != null, "and pairs");
        w.now += PairingState.REQUEST_MS;
        eq(409, p.request("x", THIRD, COMMIT).status, "the second still waits");
        eq(202, p.request("x", OTHER, COMMIT).status, "the first may ask again after its minute");
    }

    /** A request nobody answers changes nothing, however often it is collected. */
    private static void aRequestNobodyAnswers() {
        World w = new World();
        PairingState p = w.start();
        PairingState.Reply r = p.request("x", OTHER, COMMIT);
        p.reveal(r.request, SECRET);
        for (int i = 0; i < 29; i++) {
            w.now += 1000;
            PairingState.Reply c = p.collect(r.request);
            if (c.status != 202 || c.token != null) fail("still waiting at " + i + " s");
        }
        passed++;
        w.now += 1000;
        eq(403, p.collect(r.request).status, "gone after 30 s");
        eq("pairing_closed", p.collect(r.request).error, "lapsed, not denied");
        check(p.accept(r.request) == null, "the viewer cannot allow it after that");
        check(!p.isPaired() && w.saves == 0, "nothing was stored");
    }

    private static void deadlines() {
        // On screen, the viewer has two minutes; that is counted once.
        World w = new World();
        PairingState p = w.start();
        PairingState.Reply r = p.request("Home Assistant", HA, COMMIT);
        p.reveal(r.request, SECRET);
        w.now += PairingState.REQUEST_MS - 1000;
        check(p.shown(r.request), "shown in the last second");
        eq(120L, p.collect(r.request).seconds, "the viewer gets two minutes from then");
        w.now += 60_000;
        check(p.shown(r.request), "shown again (the screen was reopened)");
        eq(60L, p.collect(r.request).seconds, "which does not start the two minutes afresh");
        w.now += 59_000;
        check(p.accept(r.request) != null, "allowed in the last second");
        w.now += PairingState.COLLECT_MS - 1;
        eq(200, p.collect(r.request).status, "collected within the time allowed after that");

        // Shown but never answered: it lapses, and the address waits.
        w = new World();
        p = w.start();
        r = p.request("Home Assistant", HA, COMMIT);
        p.reveal(r.request, SECRET);
        p.shown(r.request);
        w.now += PairingState.DECIDE_MS;
        eq(403, p.collect(r.request).status, "unanswered, it lapses");
        eq(409, p.request("Home Assistant", HA, COMMIT).status, "and the address waits before the next");

        // Allowed but never collected: it lapses, and that is not held against the client.
        w = new World();
        p = w.start();
        r = p.request("Home Assistant", HA, COMMIT);
        p.reveal(r.request, SECRET);
        p.shown(r.request);
        p.accept(r.request);
        w.now += PairingState.COLLECT_MS;
        eq(403, p.collect(r.request).status, "an allowed request lapses too");
        check(!p.isPaired(), "without pairing");
        eq(202, p.request("Home Assistant", HA, COMMIT).status, "and the client may ask again at once");
    }

    /** The token in use stays valid until a new one has been collected. */
    private static void repairing() {
        World w = new World();
        PairingState p = w.start();
        PairingState.Reply r = p.request("Home Assistant", HA, COMMIT);
        p.reveal(r.request, SECRET);
        p.accept(r.request);
        String first = p.collect(r.request).token;

        PairingState.Reply r2 = p.request("Home Assistant", HA, COMMIT);
        check(p.authorized("Bearer " + first), "asking again does not end the pairing");
        p.reveal(r2.request, SECRET);
        p.accept(r2.request);
        check(p.authorized("Bearer " + first), "nor does the viewer's answer");
        String second = p.collect(r2.request).token;
        check(second != null && !second.equals(first), "a new token");
        check(!p.authorized("Bearer " + first) && p.authorized("Bearer " + second), "which replaces the old one");

        // A request by someone else, denied or left to lapse, leaves the pairing alone.
        PairingState.Reply other = p.request("Home Assistant", OTHER, COMMIT);
        p.reveal(other.request, SECRET);
        p.deny(other.request);
        check(p.authorized("Bearer " + second), "a denied request leaves the pairing untouched");
        other = p.request("Home Assistant", THIRD, COMMIT);
        w.now += PairingState.REQUEST_MS;
        eq(403, p.collect(other.request).status, "lapsed");
        check(p.authorized("Bearer " + second), "so does a lapsed one");
        eq(HA, p.paired().peer, "it is still Home Assistant's");
    }

    private static void names() {
        eq("unknown", PairingState.clean(null), "no name");
        eq("unknown", PairingState.clean("  \r\n\t "), "nothing printable");
        eq("Home Assistant", PairingState.clean(" Home\r\n Assistant\u0000 "), "control characters are dropped");
        String longName = PairingState.clean(new String(new char[500]).replace('\0', 'x'));
        eq(PairingState.MAX_NAME, longName.length(), "length is capped");
    }

    // ---- helpers ---------------------------------------------------------------

    /** 32 bytes counting up from {@code first}. */
    private static byte[] run(int first) {
        byte[] b = new byte[32];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (first + i);
        return b;
    }

    private static byte[] fill(int value) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static void eq(Object expected, Object actual, String what) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) fail(what + ": expected " + expected + ", got " + actual);
        passed++;
    }

    private static void check(boolean ok, String what) {
        if (!ok) fail(what);
        passed++;
    }

    private static void fail(String message) {
        System.err.println("FAIL: " + message);
        System.exit(1);
    }
}
