package pl.photopreview.ui

import pl.photopreview.SessionStatus

fun sessionStatusText(s: SessionStatus): String = when (s) {
    is SessionStatus.Idle -> "Bezczynny"
    is SessionStatus.Listening -> "Oczekiwanie na podgląd (port ${s.port})"
    is SessionStatus.Connecting -> "Łączenie: ${s.target}"
    is SessionStatus.Connected -> "Połączono: ${s.peer}"
    is SessionStatus.Error -> "Błąd: ${s.message}"
}
