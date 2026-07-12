package com.ryu.sonyremote.data

enum class MovieActionResult {
    Started,
    Stopped,
    AlreadyRecording,
    AlreadyStopped,
}

internal enum class MovieCommandDecision {
    Start,
    Stop,
    AlreadyRecording,
    AlreadyStopped,
    Unavailable,
}

internal fun decideMovieCommand(
    requestedStop: Boolean,
    canStart: Boolean,
    canStop: Boolean,
): MovieCommandDecision = when {
    requestedStop && canStop -> MovieCommandDecision.Stop
    requestedStop && canStart -> MovieCommandDecision.AlreadyStopped
    !requestedStop && canStart -> MovieCommandDecision.Start
    !requestedStop && canStop -> MovieCommandDecision.AlreadyRecording
    else -> MovieCommandDecision.Unavailable
}

internal fun recordingStateFromCapabilities(
    canStart: Boolean,
    canStop: Boolean,
    fallback: Boolean,
): Boolean = when {
    canStop -> true
    canStart -> false
    else -> fallback
}
