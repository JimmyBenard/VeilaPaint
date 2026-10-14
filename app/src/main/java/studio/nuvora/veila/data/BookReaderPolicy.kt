package studio.nuvora.veila.data

/** Pure page-turn rules shared by the Book reader and its regression tests. */
object BookReaderPolicy {
    const val swipeThresholdPx = 72f

    fun usesDoublePage(widthDp: Float, heightDp: Float, pageCount: Int): Boolean =
        widthDp > heightDp && widthDp >= 720f && pageCount > 1

    fun stride(doublePage: Boolean, pageCount: Int): Int =
        if (doublePage && pageCount > 1) 2 else 1

    fun pageAfterTurn(current: Int, delta: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        if (delta > 0 && current + delta >= pageCount) return current.coerceIn(0, pageCount - 1)
        return (current + delta).coerceIn(0, pageCount - 1)
    }

    fun pageAfterSwipe(current: Int, dragPx: Float, doublePage: Boolean, pageCount: Int): Int =
        when {
            dragPx <= -swipeThresholdPx -> pageAfterTurn(current, stride(doublePage, pageCount), pageCount)
            dragPx >= swipeThresholdPx -> pageAfterTurn(current, -stride(doublePage, pageCount), pageCount)
            else -> current.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
}
