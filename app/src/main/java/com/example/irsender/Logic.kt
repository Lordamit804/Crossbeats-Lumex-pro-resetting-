package com.example.irsender

/** Pure logic (no Android classes): NEC encoding and macro parsing. */
object Nec {
    const val CARRIER_HZ = 38000
    const val FRAME_PERIOD_MS = 108L

    private const val HDR_ON = 9000
    private const val HDR_OFF = 4500
    private const val BIT_ON = 560
    private const val ZERO_OFF = 560
    private const val ONE_OFF = 1690
    private const val REPEAT_OFF = 2250

    /**
     * Builds the 32-bit NEC word the way Amlogic logs it, for example 0xb24d4040
     * = custom code 0x4040 + scancode 0x4d (upper byte is the inverted scancode).
     */
    fun word(custom: Int, scan: Int): Long {
        val c = (custom and 0xFFFF).toLong()
        val s = (scan and 0xFF).toLong()
        val inv = (s.inv()) and 0xFFL
        return (inv shl 24) or (s shl 16) or c
    }

    /** On/off pulse lengths in microseconds, starting with "on". Bits go out LSB first. */
    fun frame(word: Long): IntArray {
        val p = ArrayList<Int>(67)
        p.add(HDR_ON)
        p.add(HDR_OFF)
        for (i in 0 until 32) {
            p.add(BIT_ON)
            val bit = (word shr i) and 1L
            p.add(if (bit == 1L) ONE_OFF else ZERO_OFF)
        }
        p.add(BIT_ON)
        return p.toIntArray()
    }

    /** The short "key still held" burst NEC remotes send every ~108 ms. */
    fun repeatBurst(): IntArray = intArrayOf(HDR_ON, REPEAT_OFF, BIT_ON)
}

sealed class Action {
    data class Tap(val scan: Int) : Action()
    data class Wait(val ms: Long) : Action()
    data class Hold(val scan: Int, val ms: Long) : Action()
}

object Macro {
    fun parseHex(text: String): Int {
        val t = text.trim().lowercase().removePrefix("0x")
        require(t.isNotEmpty()) { "empty hex value" }
        return t.toInt(16)
    }

    /** Lines like "D=0e", "UP=0x0b", "# comment". */
    fun parseKeys(text: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (raw in text.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split('=')
            require(parts.size == 2) { "bad key line: $line" }
            val value = parseHex(parts[1])
            require(value in 0..255) { "scancode must be 00-ff: $line" }
            out[parts[0].trim().uppercase()] = value
        }
        return out
    }

    /**
     * Tokens separated by spaces:
     *   NAME        tap the key
     *   NAME*3      tap three times
     *   w500        wait 500 ms
     *   hold:NAME:3000   hold the key for 3000 ms (NEC repeat bursts)
     */
    fun parse(text: String, keys: Map<String, Int>): List<Action> {
        val out = ArrayList<Action>()
        for (tok in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val t = tok.uppercase()
            when {
                t.startsWith("HOLD:") -> {
                    val p = t.split(':')
                    require(p.size == 3) { "bad hold token: $tok" }
                    val scan = keys[p[1]] ?: throw IllegalArgumentException("unknown key: ${p[1]}")
                    out.add(Action.Hold(scan, p[2].toLong()))
                }
                t.startsWith("W") && t.drop(1).all { it.isDigit() } && t.length > 1 ->
                    out.add(Action.Wait(t.drop(1).toLong()))
                t.contains('*') -> {
                    val p = t.split('*')
                    require(p.size == 2) { "bad repeat token: $tok" }
                    val scan = keys[p[0]] ?: throw IllegalArgumentException("unknown key: ${p[0]}")
                    repeat(p[1].toInt()) { out.add(Action.Tap(scan)) }
                }
                else -> {
                    val scan = keys[t] ?: throw IllegalArgumentException("unknown key: $tok")
                    out.add(Action.Tap(scan))
                }
            }
        }
        return out
    }
}
