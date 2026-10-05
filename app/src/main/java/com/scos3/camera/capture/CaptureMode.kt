package com.scos3.camera.capture

/**
 * Capture modes the SC OS3 workflow supports. Day 1 only implements [SINGLE];
 * the other modes are introduced with the capture engine in the next phase.
 */
enum class CaptureMode {
    SINGLE,
    BURST,
    AUTO,
    FACE,
}
