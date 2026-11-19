package com.braviafanboy.braviaagent.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV's device unique id as it appears in its
 * {@code _sonysmarthome._tcp} mDNS instance name,
 * {@code <device name>-<40 hex characters>}.
 *
 * <p>The instance name is one DNS label of at most 63 bytes (Sony's soundbars
 * stop at 60 characters), so a long device name leaves no room for the whole
 * id: the name is cut short and the id loses its end. Nothing else in the
 * announcement carries it. What is left still tells the TV apart, so it is
 * taken as it is; whoever compares it with a whole id compares beginnings.
 */
public final class DeviceId {
    /** The fewest id characters taken as an identity. */
    public static final int MIN_PARTIAL_LENGTH = 8;

    private static final Pattern WHOLE = Pattern.compile("-([0-9a-fA-F]{40})");
    private static final Pattern CUT_SHORT = Pattern.compile("-([0-9a-fA-F]{" + MIN_PARTIAL_LENGTH + ",39})$");

    private DeviceId() {
    }

    /**
     * The id in an instance name, in lower case: whole, or only its beginning
     * when the name was cut short. Null when the name carries none.
     */
    public static String fromInstanceName(String name) {
        if (name == null) return null;
        Matcher m = WHOLE.matcher(name);
        if (!m.find()) {
            m = CUT_SHORT.matcher(name);
            if (!m.find()) return null;
        }
        return m.group(1).toLowerCase(Locale.ROOT);
    }
}
