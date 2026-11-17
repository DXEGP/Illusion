package io.github.dxegp.render.frame.vanilla

internal data class PreparedBinding(
    val handle: Long,
    val firstSubresource: Int = 0,
    val subresourceCount: Int = 1,
) {
    init {
        require(handle != 0L && firstSubresource >= 0 && subresourceCount > 0)
    }
}
