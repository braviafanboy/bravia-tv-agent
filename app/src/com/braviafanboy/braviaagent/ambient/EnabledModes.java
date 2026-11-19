package com.braviafanboy.braviaagent.ambient;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The picture modes ambient brightness is switched on in.
 *
 * <p>It is a setting of each picture mode, and it is remembered: switching to
 * a mode it is off in leaves that mode's brightness alone, and switching back
 * to one it is on in carries on there. A mode nobody switched it on in is off.
 *
 * <p>Switching it on without naming a mode means "the mode the TV is in",
 * which the caller may not know yet (it is read from the TV): that is held as
 * {@link #pending} and goes to the first mode seen ({@link #adopt}). The same
 * is done with the one switch for the whole TV that agents before 0.10 kept.
 *
 * <p>Not thread-safe: the controller guards it. Pure Java, tested on a desktop
 * JVM.
 */
public final class EnabledModes {
    private final Set<String> on = new LinkedHashSet<>();
    private boolean pending;

    /** Whether it is on in {@code mode}; before the mode is known, whether it was just switched on. */
    public boolean isOn(String mode) {
        return mode == null ? pending : on.contains(mode);
    }

    /** Whether there is any mode to run in, now or as soon as it is known. */
    public boolean any() {
        return pending || !on.isEmpty();
    }

    /**
     * Switch it on or off in {@code mode}; with no mode, on in the mode the
     * TV turns out to be in, or off for a switch-on that is still waiting.
     * Returns whether anything changed.
     */
    public boolean set(String mode, boolean value) {
        if (mode == null) {
            if (pending == value) return false;
            pending = value;
            return true;
        }
        return value ? on.add(mode) : on.remove(mode);
    }

    /** The TV is in {@code mode}: a switch-on that was waiting for it applies here. Returns whether one did. */
    public boolean adopt(String mode) {
        if (!pending || mode == null) return false;
        pending = false;
        on.add(mode);
        return true;
    }

    /** A switch-on that still waits for the picture mode to be known. */
    public boolean pending() {
        return pending;
    }

    public Set<String> modes() {
        return Collections.unmodifiableSet(on);
    }
}
