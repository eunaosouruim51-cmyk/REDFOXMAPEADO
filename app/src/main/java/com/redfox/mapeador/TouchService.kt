package com.redfox.mapeador

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

class TouchService : AccessibilityService() {

    private class Botao(
        val texto: String,
        val tecla: Int,
        val xPadrao: Float,
        val yPadrao: Float
    ) {
        var view: TextView? = null
        var params: WindowManager.LayoutParams? = null
    }

    private val botoes = listOf(
        Botao("J\nTiro", KeyEvent.KEYCODE_J, 0.85f, 0.55f),
        Botao("Espaço\nPular", KeyEvent.KEYCODE_SPACE, 0.92f, 0.40f),
        Botao("C\nAgachar", KeyEvent.KEYCODE_C, 0.92f, 0.70f),
        Botao("R\nRecarga", KeyEvent.KEYCODE_R, 0.75f, 0.80f),
        Botao("E\nUsar", KeyEvent.KEYCODE_E, 0.70f, 0.30f),
        Botao("B\nComprar", KeyEvent.KEYCODE_B, 0.50f, 0.10f),
        Botao("1\nArma 1", KeyEvent.KEYCODE_1, 0.60f, 0.90f),
        Botao("2\nArma 2", KeyEvent.KEYCODE_2, 0.67f, 0.90f),
        Botao("3\nArma 3", KeyEvent.KEYCODE_3, 0.74f, 0.90f)
    )

    private val flagsBase = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var menu: TextView? = null
    private var modoEdicao = true
    private var criado = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (criado) return
        criado = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("mapeador", Context.MODE_PRIVATE)
        criarBotoes()
        criarMenu()
        aplicarModo()
    }

    private fun criarBotoes() {
        val d = resources.displayMetrics
        val larg = maxOf(d.widthPixels, d.heightPixels)
        val alt = minOf(d.widthPixels, d.heightPixels)
        val tam = (68 * d.density).toInt()

        for (b in botoes) {
            val tv = TextView(this).apply {
                text = b.texto
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC1565C0"))
                    setStroke(3, Color.WHITE)
                }
            }

            val lp = WindowManager.LayoutParams(
                tam, tam,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flagsBase,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt("k${b.tecla}_x", (larg * b.xPadrao).toInt() - tam / 2)
                y = prefs.getInt("k${b.tecla}_y", (alt * b.yPadrao).toInt() - tam / 2)
            }

            var iniX = 0
            var iniY = 0
            var toqueX = 0f
            var toqueY = 0f
            tv.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        iniX = lp.x
                        iniY = lp.y
                        toqueX = ev.rawX
                        toqueY = ev.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = iniX + (ev.rawX - toqueX).toInt()
                        lp.y = iniY + (ev.rawY - toqueY).toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit()
                            .putInt("k${b.tecla}_x", lp.x)
                            .putInt("k${b.tecla}_y", lp.y)
                            .apply()
                    }
                }
                true
            }

            wm.addView(tv, lp)
            b.view = tv
            b.params = lp
        }
    }

    private fun criarMenu() {
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(32, 12, 32, 12)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#CC000000"))
                setStroke(3, Color.WHITE)
            }
            setOnClickListener { alternarModo() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flagsBase,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 8
        }
        wm.addView(tv, lp)
        menu = tv
    }

    private fun alternarModo() {
        modoEdicao = !modoEdicao
        aplicarModo()
    }

    private fun aplicarModo() {
        for (b in botoes) {
            val tv = b.view ?: continue
            val lp = b.params ?: continue
            if (modoEdicao) {
                lp.flags = flagsBase
                tv.alpha = 0.9f
            } else {
                lp.flags = flagsBase or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                tv.alpha = 0.3f
            }
            wm.updateViewLayout(tv, lp)
        }
        menu?.text = if (modoEdicao) "JOGAR" else "EDITAR"
        menu?.alpha = if (modoEdicao) 0.95f else 0.5f
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_F1) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                alternarModo()
            }
            return true
        }
        if (modoEdicao) return false

        val b = botoes.firstOrNull { it.tecla == event.keyCode } ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val v = b.view
            if (v != null) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                tap(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    override fun onDestroy() {
        if (criado) {
            for (b in botoes) {
                b.view?.let {
                    try { wm.removeView(it) } catch (e: Exception) {}
                }
                b.view = null
            }
            menu?.let {
                try { wm.removeView(it) } catch (e: Exception) {}
            }
            menu = null
            criado = false
        }
        super.onDestroy()
    }
}
