package com.braviafanboy.braviaagent.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Pairing: how a client gets the bearer token every other request must carry.
 *
 * <p>A person at the TV decides. A client asks ({@link #request}), which shows
 * nothing on the TV. When the agent's screen is opened while a request waits,
 * it asks the viewer to allow or deny it, and shows a six-digit code. The
 * client shows the same code to its user, who allows the request only if the
 * two match. Only then does the client collect the token ({@link #collect}).
 *
 * <p>The code is what ties the viewer's answer to one client and one agent. It
 * is derived from a secret of the client's, a secret of the agent's and the
 * agent's certificate, and neither side can steer it: the client commits to
 * its secret (sends its SHA-256) before it sees the agent's, and reveals it
 * only afterwards ({@link #reveal}). So
 * <ul>
 * <li>another client's request shows a different code than the one the user
 *     is looking at, and
 * <li>something that sits between the client and the agent, answering the
 *     client with a certificate of its own, makes the client compute its code
 *     from that certificate, which again differs from the TV's.
 * </ul>
 * Either way the codes disagree and the viewer denies; guessing right is one
 * chance in a million per request a person looks at.
 *
 * <p>There is one request at a time. One that nobody allowed lapses, or is
 * denied, and the address it came from then waits {@link #COOLDOWN_MS} before
 * it may ask again, so that one address cannot keep the place taken. (Several
 * addresses taking turns can. That keeps others from pairing and gets them
 * nothing.) The token in use stays valid until a new one has actually been
 * collected. Without a token nothing is authorised.
 *
 * <p>Pure Java (clock, storage and randomness are passed in), so it is tested
 * on a desktop JVM.
 */
public final class PairingState {
    /** How long a request waits to be put before the viewer. */
    public static final long REQUEST_MS = 30_000;
    /** How long the viewer has to answer once it is on screen. */
    public static final long DECIDE_MS = 120_000;
    /** After it is allowed, the client has at least this long to collect. */
    public static final long COLLECT_MS = 15_000;
    /** How long an address whose request lapsed or was denied waits before the next. */
    public static final long COOLDOWN_MS = 60_000;
    public static final int MAX_NAME = 64;
    /** Bytes in each side's secret, and in a SHA-256. */
    private static final int SECRET_BYTES = 32;
    private static final byte[] CODE_LABEL = "bravia-agent pairing 1".getBytes(StandardCharsets.US_ASCII);

    public interface Clock {
        /** Milliseconds that only go forward (deadlines). */
        long elapsedMs();

        /** Wall-clock milliseconds (the "paired at" record). */
        long wallMs();
    }

    /** Where the pairing is kept across restarts. */
    public interface Storage {
        /** The stored pairing, or null. */
        Paired load();

        void save(Paired paired);

        void clear();
    }

    /** A completed pairing. */
    public static final class Paired {
        public final String token;
        /** What the client called itself. */
        public final String name;
        /** The address it paired from. */
        public final String peer;
        public final long atMs;

        public Paired(String token, String name, String peer, long atMs) {
            this.token = token;
            this.name = name;
            this.peer = peer;
            this.atMs = atMs;
        }
    }

    /** A request on its way: asked, revealed (it has a code), shown, allowed. */
    public static final class Request {
        public final String id;
        public final String name;
        public final String peer;
        private final String commit;
        private final byte[] nonce;
        private long expiresAt;
        private String code;
        private boolean shown;
        private boolean accepted;

        private Request(String id, String name, String peer, String commit, byte[] nonce, long expiresAt) {
            this.id = id;
            this.name = name;
            this.peer = peer;
            this.commit = commit;
            this.nonce = nonce;
            this.expiresAt = expiresAt;
        }

        /** The six digits to show the viewer; null until the client has revealed its secret. */
        public String code() {
            return code;
        }

        public boolean accepted() {
            return accepted;
        }
    }

    /** The answer to {@code POST /v1/pair}. */
    public static final class Reply {
        /** 200 (token), 202 (request waiting), 400 (malformed), 403 (no such request, or denied), 409 (ask again later). */
        public final int status;
        /** {@code commit_required}, {@code pairing_closed}, {@code pairing_denied} or {@code pairing_busy}; null for 200 and 202. */
        public final String error;
        /** The request id (202). */
        public final String request;
        /** The agent's secret, hex: only in the answer to the request itself. */
        public final String nonce;
        /** Whether the viewer has allowed the request (202). */
        public final boolean accepted;
        /** The token (200). */
        public final String token;
        /** 202: seconds until the request lapses. 409: seconds until it is worth asking again. */
        public final long seconds;

        private Reply(int status, String error, String request, String nonce, boolean accepted, String token,
                long seconds) {
            this.status = status;
            this.error = error;
            this.request = request;
            this.nonce = nonce;
            this.accepted = accepted;
            this.token = token;
            this.seconds = seconds;
        }

        private static Reply refused(int status, String error, long seconds) {
            return new Reply(status, error, null, null, false, null, seconds);
        }
    }

    private final Clock clock;
    private final Storage storage;
    private final Random random;
    private Paired paired;
    private Request pending;
    /** The request the viewer last denied: its client is told so, once it asks. */
    private String deniedId;
    /** SHA-256 of the agent's certificate, which the code is tied to; null until it is known. */
    private byte[] certificate;
    /**
     * When each address whose request lapsed or was denied may ask again. It
     * stays small: a request waits before it can lapse, and an entry goes
     * once its time is over.
     */
    private final Map<String, Long> cooldowns = new HashMap<>();

    /** {@code random} must be a {@link java.security.SecureRandom} outside tests. */
    public PairingState(Clock clock, Storage storage, Random random) {
        this.clock = clock;
        this.storage = storage;
        this.random = random;
        this.paired = storage.load();
    }

    /** The SHA-256 of the certificate the agent serves (64 hex characters). Without it nothing can be paired. */
    public synchronized void setCertificate(String fingerprintHex) {
        certificate = unhex(fingerprintHex, SECRET_BYTES);
    }

    public synchronized boolean isPaired() {
        return paired != null;
    }

    /** The pairing in use, or null. */
    public synchronized Paired paired() {
        return paired;
    }

    /** The request that is waiting, or null. */
    public synchronized Request pending() {
        expire(clock.elapsedMs());
        return pending;
    }

    /** Whether an {@code Authorization} header carries the token in use. Unpaired, nothing does. */
    public synchronized boolean authorized(String authorizationHeader) {
        if (paired == null || authorizationHeader == null) return false;
        String h = authorizationHeader.trim();
        if (!h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return constantTimeEquals(h.substring(7).trim(), paired.token);
    }

    /**
     * {@code POST /v1/pair?name=…&commit=…}: a client asks to pair. {@code
     * commitHex} is the SHA-256 of the secret it will reveal next. The answer
     * carries the agent's own secret.
     */
    public synchronized Reply request(String name, String peer, String commitHex) {
        long now = clock.elapsedMs();
        expire(now);
        if (unhex(commitHex, SECRET_BYTES) == null) return Reply.refused(400, "commit_required", 0);
        if (pending != null) return Reply.refused(409, "pairing_busy", seconds(pending.expiresAt - now));
        Long mayAskAt = peer == null ? null : cooldowns.get(peer);
        if (mayAskAt != null) return Reply.refused(409, "pairing_busy", seconds(mayAskAt - now));
        byte[] nonce = new byte[SECRET_BYTES];
        random.nextBytes(nonce);
        pending = new Request(hex(16), clean(name), peer, commitHex.toLowerCase(), nonce, now + REQUEST_MS);
        deniedId = null;
        return new Reply(202, null, pending.id, hex(nonce), false, null, seconds(REQUEST_MS));
    }

    /**
     * {@code POST /v1/pair?request=<id>&reveal=…}: the client shows the
     * secret it committed to. From here the request has its code and can be
     * put before the viewer. A secret that does not match ends the request.
     */
    public synchronized Reply reveal(String requestId, String secretHex) {
        long now = clock.elapsedMs();
        expire(now);
        if (!isPending(requestId)) return closed(requestId);
        byte[] secret = unhex(secretHex, SECRET_BYTES);
        if (secret == null || certificate == null || !constantTimeEquals(hex(sha256(secret)), pending.commit)) {
            pending = null;
            return Reply.refused(403, "pairing_closed", 0);
        }
        if (pending.code == null) pending.code = code(secret, pending.nonce, certificate);
        return waiting(now);
    }

    /** The request is on the viewer's screen: give them time to answer. False when it is gone. */
    public synchronized boolean shown(String requestId) {
        long now = clock.elapsedMs();
        expire(now);
        if (!isPending(requestId) || pending.code == null) return false;
        if (!pending.shown && !pending.accepted) {
            pending.shown = true;
            pending.expiresAt = now + DECIDE_MS;
        }
        return true;
    }

    /** The viewer allowed the request. Returns it, or null when it is gone. */
    public synchronized Request accept(String requestId) {
        long now = clock.elapsedMs();
        expire(now);
        if (!isPending(requestId) || pending.code == null) return null;
        pending.accepted = true;
        // Whatever time the viewer had left, the client only needs a moment.
        pending.expiresAt = now + COLLECT_MS;
        return pending;
    }

    /** The viewer denied the request. Returns it, or null when it is gone. */
    public synchronized Request deny(String requestId) {
        long now = clock.elapsedMs();
        expire(now);
        if (!isPending(requestId) || pending.accepted) return null;
        Request denied = pending;
        if (denied.peer != null) cooldowns.put(denied.peer, now + COOLDOWN_MS);
        deniedId = denied.id;
        pending = null;
        return denied;
    }

    /** {@code POST /v1/pair?request=<id>}: the client that asked collects its token. */
    public synchronized Reply collect(String requestId) {
        long now = clock.elapsedMs();
        expire(now);
        if (!isPending(requestId)) return closed(requestId);
        if (!pending.accepted) return waiting(now);
        paired = new Paired(hex(32), pending.name, pending.peer, clock.wallMs());
        storage.save(paired);
        pending = null;
        return new Reply(200, null, null, null, true, paired.token, 0);
    }

    /** Forget the pairing and any request. */
    public synchronized void clear() {
        paired = null;
        pending = null;
        deniedId = null;
        storage.clear();
    }

    /**
     * The code both sides show: the first four bytes of SHA-256(label, 0,
     * client secret, agent secret, certificate SHA-256) as a number, modulo a
     * million, six digits.
     */
    public static String code(byte[] clientSecret, byte[] agentSecret, byte[] certificateSha256) {
        MessageDigest md = digest();
        md.update(CODE_LABEL);
        md.update((byte) 0);
        md.update(clientSecret);
        md.update(agentSecret);
        md.update(certificateSha256);
        byte[] d = md.digest();
        long n = ((d[0] & 0xFFL) << 24) | ((d[1] & 0xFFL) << 16) | ((d[2] & 0xFFL) << 8) | (d[3] & 0xFFL);
        String digits = Long.toString(n % 1_000_000L);
        return "000000".substring(digits.length()) + digits;
    }

    private boolean isPending(String requestId) {
        return pending != null && requestId != null && constantTimeEquals(requestId, pending.id);
    }

    private Reply closed(String requestId) {
        boolean denied = deniedId != null && requestId != null && constantTimeEquals(requestId, deniedId);
        return Reply.refused(403, denied ? "pairing_denied" : "pairing_closed", 0);
    }

    private Reply waiting(long now) {
        return new Reply(202, null, pending.id, null, pending.accepted, null, seconds(pending.expiresAt - now));
    }

    /** Drop the request if its time is over, and the waits that are. */
    private void expire(long now) {
        cooldowns.values().removeIf(until -> now >= until);
        if (pending == null || now < pending.expiresAt) return;
        long mayAskAt = pending.expiresAt + COOLDOWN_MS;
        if (!pending.accepted && pending.peer != null && now < mayAskAt) cooldowns.put(pending.peer, mayAskAt);
        pending = null;
    }

    private static long seconds(long ms) {
        return ms <= 0 ? 0 : (ms + 999) / 1000;
    }

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return hex(b);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /** The bytes of a hex string of exactly {@code bytes} bytes, or null. */
    private static byte[] unhex(String s, int bytes) {
        if (s == null || s.length() != bytes * 2) return null;
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static byte[] sha256(byte[] data) {
        return digest().digest(data);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);  // every Java platform has it
        }
    }

    /** A client's name for itself, made safe to show and to log: printable, trimmed, at most {@link #MAX_NAME} characters. */
    static String clean(String name) {
        if (name == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < MAX_NAME; i++) {
            char c = name.charAt(i);
            if (!Character.isISOControl(c) && !Character.isSurrogate(c)) sb.append(c);
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "unknown" : s;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
