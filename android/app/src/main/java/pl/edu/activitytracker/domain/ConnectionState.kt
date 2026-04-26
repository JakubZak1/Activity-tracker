package pl.edu.activitytracker.domain

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val transport: Transport) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

enum class Transport {
    Mock,
    Ble,
}

fun ConnectionState.label(): String {
    return when (this) {
        ConnectionState.Disconnected -> "Disconnected"
        ConnectionState.Scanning -> "Scanning"
        ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Connected -> "Connected (${transport.name.lowercase()})"
        is ConnectionState.Failed -> "Error: $message"
    }
}
