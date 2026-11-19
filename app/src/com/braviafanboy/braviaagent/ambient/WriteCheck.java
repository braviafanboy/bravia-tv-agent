package com.braviafanboy.braviaagent.ambient;

/**
 * The last look at the TV before ambient brightness writes the Brightness
 * slider: is the slider still the one the value was worked out for?
 *
 * <p>What the loop knows of the TV is up to five seconds old, and it writes
 * up to once a second. In between, the viewer may have moved the slider, the
 * TV may have changed picture mode, or an HDR clip may have started, which
 * swaps in the mode's separate HDR values. Each of these means the value in
 * hand belongs to another slider, and writing it would spoil a setting that
 * nothing puts back. So the slider, the picture mode and whether HDR is
 * showing are read again immediately before every write, and only an
 * unchanged, readable answer lets the write go ahead.
 *
 * <p>Pure Java, tested on a desktop JVM.
 */
public final class WriteCheck {
    private WriteCheck() {
    }

    public enum Verdict {
        /** Nothing has changed: write. */
        WRITE,
        /** The TV did not answer in full: not now. */
        UNREADABLE,
        /** The TV is in another picture mode. */
        MODE_CHANGED,
        /** HDR is showing: these are not the SDR values. */
        HDR,
        /** The slider is not where ambient brightness left it: the viewer moved it. */
        MOVED
    }

    /**
     * @param mode the picture mode the value was worked out for
     * @param lastWritten what ambient brightness last wrote, or saw, there
     * @param liveMode the picture mode the TV reports now, or null
     * @param liveBrightness the slider now, or negative
     * @param hdrShowing whether the TV, or the client, says HDR is showing
     */
    public static Verdict verdict(String mode, int lastWritten, String liveMode, int liveBrightness,
            boolean hdrShowing) {
        if (liveMode == null || liveMode.isEmpty() || liveBrightness < 0) return Verdict.UNREADABLE;
        if (!liveMode.equals(mode)) return Verdict.MODE_CHANGED;
        // Before the slider is compared: in HDR it usually differs as well,
        // and that is not the viewer's hand.
        if (hdrShowing) return Verdict.HDR;
        if (liveBrightness != lastWritten) return Verdict.MOVED;
        return Verdict.WRITE;
    }
}
