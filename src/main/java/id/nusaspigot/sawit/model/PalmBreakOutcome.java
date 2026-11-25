package id.nusaspigot.sawit.model;

/**
 * Result of a player attempting to break a planted oil palm block or furniture hitbox.
 */
public enum PalmBreakOutcome {
    NOT_HANDLED,
    BLOCKED_KEEP_PALM,
    UPROOTED_REMOVED
}
