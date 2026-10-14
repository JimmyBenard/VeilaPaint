package studio.nuvora.veila.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BookReaderPolicyTest {
    @Test
    fun portraitTurnsOnePageInBothDirections() {
        assertEquals(4, BookReaderPolicy.pageAfterSwipe(3, -100f, false, 8))
        assertEquals(2, BookReaderPolicy.pageAfterSwipe(3, 100f, false, 8))
    }

    @Test
    fun landscapeTurnsAVisibleSpreadWithoutSkippingBounds() {
        assertEquals(4, BookReaderPolicy.pageAfterSwipe(2, -100f, true, 6))
        assertEquals(0, BookReaderPolicy.pageAfterSwipe(0, 100f, true, 6))
        assertEquals(5, BookReaderPolicy.pageAfterSwipe(5, -100f, true, 6))
    }

    @Test
    fun shortOrVerticalDragKeepsTheLastReadingPosition() {
        assertEquals(3, BookReaderPolicy.pageAfterSwipe(3, -20f, false, 9))
        assertEquals(0, BookReaderPolicy.pageAfterTurn(0, -1, 0))
    }

    @Test
    fun widePortraitStillUsesOnePageAndTheLastSpreadDoesNotRepeatAPage() {
        assertEquals(false, BookReaderPolicy.usesDoublePage(900f, 1200f, 8))
        assertEquals(true, BookReaderPolicy.usesDoublePage(1200f, 900f, 8))
        assertEquals(false, BookReaderPolicy.usesDoublePage(700f, 500f, 8))
        assertEquals(4, BookReaderPolicy.pageAfterSwipe(4, -100f, true, 6))
    }
}
