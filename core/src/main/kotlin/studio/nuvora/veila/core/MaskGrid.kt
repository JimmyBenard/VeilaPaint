package studio.nuvora.veila.core

/** Exact 0..1000 mask, authoritative Long sum, 64px dirty tiles. */
class MaskGrid(val width: Int, val height: Int, initial: ShortArray? = null) {
    companion object {
        const val MAX = 1000
        const val TILE = 64
    }

    init {
        require(width > 0 && height > 0 && width.toLong() * height <= ProjectRules.MAX_PIXELS)
    }

    private val values = initial?.copyOf() ?: ShortArray(width * height)
    val columns = (width + TILE - 1) / TILE
    val rows = (height + TILE - 1) / TILE
    private val dirty = linkedSetOf<Int>()
    var sum = 0L
        private set

    var saturated = 0
        private set

    init {
        require(values.size == width * height && values.all { it.toInt() in 0..MAX })
        for (v in values) {
            sum += v.toInt()
            if (v.toInt() == MAX) saturated++
        }
        markAllDirty()
    }

    val fraction
        get() = sum.toDouble() / (values.size.toLong() * MAX)

    val complete
        get() = saturated == values.size

    fun get(x: Int, y: Int) = values[y * width + x].toInt()

    internal fun set(x: Int, y: Int, value: Int) {
        val i = y * width + x
        val old = values[i].toInt()
        if (old == value) return
        require(value in 0..MAX)
        sum += value - old
        if (old == MAX) saturated--
        if (value == MAX) saturated++
        values[i] = value.toShort()
        dirty += tileAt(x, y)
    }

    fun copyValues() = values.copyOf()

    fun tileAt(x: Int, y: Int) = (y / TILE) * columns + x / TILE

    fun tileWidth(id: Int) = minOf(TILE, width - (id % columns) * TILE)

    fun tileHeight(id: Int) = minOf(TILE, height - (id / columns) * TILE)

    fun tileOrigin(id: Int) = Pair((id % columns) * TILE, (id / columns) * TILE)

    fun copyTile(id: Int): ShortArray {
        val (x, y) = tileOrigin(id)
        val w = tileWidth(id)
        val h = tileHeight(id)
        return ShortArray(w * h).also { out ->
            for (r in 0 until h) values.copyInto(
                out,
                r * w,
                (y + r) * width + x,
                (y + r) * width + x + w,
            )
        }
    }

    internal fun replaceTile(id: Int, data: ShortArray) {
        require(id in 0 until columns * rows)
        val (x, y) = tileOrigin(id)
        val w = tileWidth(id)
        val h = tileHeight(id)
        require(data.size == w * h)
        for (r in 0 until h) for (c in 0 until w) set(x + c, y + r, data[r * w + c].toInt())
    }

    internal fun fill(value: Int) {
        require(value in 0..MAX)
        values.fill(value.toShort())
        sum = values.size.toLong() * value
        saturated = if (value == MAX) values.size else 0
        markAllDirty()
    }

    fun consumeDirty() = dirty.toList().also { dirty.clear() }

    fun markAllDirty() {
        dirty.addAll(0 until columns * rows)
    }
}
