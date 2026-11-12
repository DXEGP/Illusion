package io.github.dxegp.bootstrap.earlywindow.service

import org.lwjgl.sdl.SDLVideo

class EarlyWindowLease(private var window: Long) {
    private var claimed = false
    private var closed = false

    init {
        if (window == 0L) {
            throw IllegalArgumentException("An early window lease requires a window")
        }
    }

    @Synchronized
    fun claim(): Long {
        if (closed || claimed) {
            return 0L
        }
        claimed = true
        return window
    }

    @Synchronized
    fun isClaimed(): Boolean = claimed

    @Synchronized
    fun closeUnclaimed() {
        if (closed || claimed) {
            return
        }
        closed = true
        val handle = window
        window = 0L
        SDLVideo.SDL_DestroyWindow(handle)
    }
}
