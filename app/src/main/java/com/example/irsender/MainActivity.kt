package com.example.irsender

import android.app.Activity
import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private var ir: ConsumerIrManager? = null
    @Volatile private var running = false

    private lateinit var status: TextView
    private lateinit var customEt: EditText
    private lateinit var keysEt: EditText
    private lateinit var macroEt: EditText
    private lateinit var gapEt: EditText
    private lateinit var loopsEt: EditText
    private lateinit var pauseEt: EditText

    private val prefs by lazy { getSharedPreferences("ir", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }

        status = TextView(this).apply {
            textSize = 16f
            text = if (ir?.hasIrEmitter() == true) "IR emitter found" else "No IR emitter on this phone"
        }
        col.addView(status)

        col.addView(label("NEC custom code (hex, 4 digits, as Amlogic prints it, e.g. fb04)"))
        customEt = edit("fb04", prefs.getString("custom", "") ?: "", false)
        col.addView(customEt)

        col.addView(label("Keys, one per line: NAME=scancode (hex). Example: D=0e"))
        keysEt = edit("D=0e\nU=0b\nP=4d", prefs.getString("keys", "") ?: "", true)
        col.addView(keysEt)

        col.addView(label("Macro. Example: D*3 U*2 w1000 hold:D:3000"))
        macroEt = edit("D*3 U*2", prefs.getString("macro", "") ?: "", true)
        col.addView(macroEt)

        col.addView(label("Gap between taps (ms)"))
        gapEt = edit("200", prefs.getString("gap", "200") ?: "200", false)
        gapEt.inputType = InputType.TYPE_CLASS_NUMBER
        col.addView(gapEt)

        col.addView(label("Repeat whole macro (times)"))
        loopsEt = edit("1", prefs.getString("loops", "1") ?: "1", false)
        loopsEt.inputType = InputType.TYPE_CLASS_NUMBER
        col.addView(loopsEt)

        col.addView(label("Pause between repeats (ms)"))
        pauseEt = edit("1000", prefs.getString("pause", "1000") ?: "1000", false)
        pauseEt.inputType = InputType.TYPE_CLASS_NUMBER
        col.addView(pauseEt)

        col.addView(Button(this).apply {
            text = "Send macro"
            setOnClickListener { start(scan = false) }
        })
        col.addView(Button(this).apply {
            text = "Scan scancodes 00-FF (to find the power key)"
            setOnClickListener { start(scan = true) }
        })
        col.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { running = false }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setPadding(0, 28, 0, 4)
    }

    private fun edit(hint: String, value: String, multiline: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(value)
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP
        } else {
            inputType = InputType.TYPE_CLASS_TEXT
        }
    }

    private fun say(msg: String) = runOnUiThread { status.text = msg }

    private fun start(scan: Boolean) {
        if (running) return
        val mgr = ir
        if (mgr == null || !mgr.hasIrEmitter()) {
            say("No IR emitter on this phone")
            return
        }
        val custom: Int
        val actions: List<Action>
        val gap: Long
        val loops: Int
        val pause: Long
        try {
            custom = Macro.parseHex(customEt.text.toString())
            gap = gapEt.text.toString().trim().toLong()
            loops = loopsEt.text.toString().trim().toInt()
            pause = pauseEt.text.toString().trim().toLong()
            actions = if (scan) emptyList()
            else Macro.parse(macroEt.text.toString(), Macro.parseKeys(keysEt.text.toString()))
        } catch (e: Exception) {
            say("Input error: ${e.message}")
            return
        }
        prefs.edit()
            .putString("custom", customEt.text.toString())
            .putString("keys", keysEt.text.toString())
            .putString("macro", macroEt.text.toString())
            .putString("gap", gapEt.text.toString())
            .putString("loops", loopsEt.text.toString())
            .putString("pause", pauseEt.text.toString())
            .apply()

        running = true
        Thread {
            try {
                if (scan) runScan(mgr, custom) else runMacro(mgr, custom, actions, gap, loops, pause)
                say(if (running) "Done" else "Stopped")
            } catch (e: Exception) {
                say("Error: ${e.message}")
            } finally {
                running = false
            }
        }.start()
    }

    private fun tap(mgr: ConsumerIrManager, custom: Int, scan: Int) {
        mgr.transmit(Nec.CARRIER_HZ, Nec.frame(Nec.word(custom, scan)))
    }

    private fun runMacro(
        mgr: ConsumerIrManager, custom: Int, actions: List<Action>,
        gap: Long, loops: Int, pause: Long
    ) {
        for (l in 1..loops) {
            say("Sending $l/$loops")
            for (a in actions) {
                if (!running) return
                when (a) {
                    is Action.Tap -> { tap(mgr, custom, a.scan); sleep(gap) }
                    is Action.Wait -> sleep(a.ms)
                    is Action.Hold -> {
                        tap(mgr, custom, a.scan)
                        var elapsed = Nec.FRAME_PERIOD_MS
                        sleep(Nec.FRAME_PERIOD_MS)
                        while (running && elapsed < a.ms) {
                            mgr.transmit(Nec.CARRIER_HZ, Nec.repeatBurst())
                            sleep(Nec.FRAME_PERIOD_MS)
                            elapsed += Nec.FRAME_PERIOD_MS
                        }
                    }
                }
            }
            sleep(pause)
        }
    }

    private fun runScan(mgr: ConsumerIrManager, custom: Int) {
        for (s in 0..255) {
            if (!running) return
            say("Scancode 0x%02x".format(s))
            tap(mgr, custom, s)
            sleep(1200)
        }
    }

    /** Sleeps in small slices so Stop works quickly. */
    private fun sleep(ms: Long) {
        var left = ms
        while (left > 0 && running) {
            val s = minOf(left, 50L)
            Thread.sleep(s)
            left -= s
        }
    }
}
