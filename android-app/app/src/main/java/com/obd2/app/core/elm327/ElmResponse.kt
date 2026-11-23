package com.obd2.app.core.elm327

/**
 * Result of a single ELM327 command exchange.
 */
sealed interface ElmResponse {
    /** Clean response; [lines] excludes echo, empty lines and the prompt. */
    data class Ok(val lines: List<String>, val durationMs: Long) : ElmResponse

    /** Adapter reported an error/message instead of data. */
    data class AdapterMessage(
        val kind: Kind,
        val text: String,
        val durationMs: Long,
    ) : ElmResponse {
        enum class Kind {
            NO_DATA,
            STOPPED,
            UNABLE_TO_CONNECT,
            CAN_ERROR,
            BUS_ERROR,
            BUS_INIT,
            BUS_BUSY,
            FLOODING,
            QUESTION_MARK,
            ERROR,
            SEARCHING,
            OK_ONLY,
            UNKNOWN,
        }
    }

    /** No prompt arrived in time. */
    data class Timeout(val partial: String, val durationMs: Long) : ElmResponse
}

val ElmResponse.durationMsLong: Long
    get() = when (this) {
        is ElmResponse.Ok -> durationMs
        is ElmResponse.AdapterMessage -> durationMs
        is ElmResponse.Timeout -> durationMs
    }

fun classifyLine(line: String): ElmResponse.AdapterMessage.Kind? {
    val u = line.uppercase().trim()
    return when {
        u == "NO DATA" -> ElmResponse.AdapterMessage.Kind.NO_DATA
        u.startsWith("NO DATA") -> ElmResponse.AdapterMessage.Kind.NO_DATA
        u == "STOPPED" -> ElmResponse.AdapterMessage.Kind.STOPPED
        u.startsWith("UNABLE TO CONNECT") -> ElmResponse.AdapterMessage.Kind.UNABLE_TO_CONNECT
        u.startsWith("CAN ERROR") -> ElmResponse.AdapterMessage.Kind.CAN_ERROR
        u.startsWith("BUS ERROR") -> ElmResponse.AdapterMessage.Kind.BUS_ERROR
        u.startsWith("BUS INIT") -> ElmResponse.AdapterMessage.Kind.BUS_INIT
        u.startsWith("SEARCHING") -> ElmResponse.AdapterMessage.Kind.SEARCHING
        u.startsWith("BUS BUSY") -> ElmResponse.AdapterMessage.Kind.BUS_BUSY
        u.startsWith("FB ERROR") -> ElmResponse.AdapterMessage.Kind.BUS_ERROR
        u.startsWith("DATA ERROR") -> ElmResponse.AdapterMessage.Kind.BUS_ERROR
        u.startsWith("BUFFER FULL") -> ElmResponse.AdapterMessage.Kind.FLOODING
        u == "?" -> ElmResponse.AdapterMessage.Kind.QUESTION_MARK
        u == "ERROR" -> ElmResponse.AdapterMessage.Kind.ERROR
        u == "OK" -> ElmResponse.AdapterMessage.Kind.OK_ONLY
        else -> null
    }
}
