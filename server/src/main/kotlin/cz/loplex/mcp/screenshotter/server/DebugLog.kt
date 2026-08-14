package cz.loplex.mcp.screenshotter.server

/**
 * Prints [message] to stderr, prefixed "[DEBUG]", only when `SCREENSHOTTER_DEBUG` is set to a
 * non-empty value - the same opt-in-env-var pattern already used for `SCREENSHOTTER_DRY_RUN_KILL`.
 *
 * A single shared helper (rather than one-off checks scattered per call site, or pulling in a full
 * logging framework like SLF4J/Logback - overkill for a project this size with no log routing or
 * aggregation need) so purely diagnostic chatter can be silenced by default without losing it for
 * anyone actively debugging (e.g. the kind of investigation in
 * `NOTES/AI/2026-08-14-kill-procps-investigation.md`). Lifecycle events and real errors stay on
 * plain `System.err.println` - those should always be visible, not opt-in.
 */
internal fun debugLog(message: String) {
    if (!System.getenv("SCREENSHOTTER_DEBUG").isNullOrEmpty()) {
        System.err.println("[DEBUG] $message")
    }
}
