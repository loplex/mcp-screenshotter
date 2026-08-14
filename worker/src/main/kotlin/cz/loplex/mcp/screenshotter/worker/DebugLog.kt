package cz.loplex.mcp.screenshotter.worker

/**
 * Prints [message] to stderr, prefixed "[DEBUG]", only when `SCREENSHOTTER_DEBUG` is set to a
 * non-empty value - the same opt-in-env-var pattern the server module uses (and the same one this
 * module already uses for `SCREENSHOTTER_DRY_RUN_KILL`-style flags).
 *
 * A single shared helper (rather than one-off checks scattered per call site, or pulling in a full
 * logging framework like SLF4J/Logback - overkill for a project this size with no log routing or
 * aggregation need) so purely diagnostic chatter (e.g. AT-SPI desktop enumeration) can be silenced
 * by default without losing it for anyone actively debugging. Lifecycle events and real errors
 * stay on plain `System.err.println` - those should always be visible, not opt-in.
 */
internal fun debugLog(message: String) {
    if (!System.getenv("SCREENSHOTTER_DEBUG").isNullOrEmpty()) {
        System.err.println("[DEBUG] $message")
    }
}
