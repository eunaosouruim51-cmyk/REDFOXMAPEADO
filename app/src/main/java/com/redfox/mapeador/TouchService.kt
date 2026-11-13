package com.redfox.mapeador

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import kotlin.math.sqrt

class TouchService : AccessibilityService() {

    private enum class Tipo { TOQUE, SEGURAR, JOYSTICK }

    private class Botao(
        val texto: String,
        val id: Int,
        val tipo: Tipo,
        val xPadrao: Float,
        val yPadrao: Float,
        val dp: Int
    ) {
        var view: TextView? = null
        var params: WindowManager.LayoutParams? = null
    }

    private val JOY_ID = -1

    private val botoes = listOf(
        Botao("WASD\nAndar", JOY_ID, Tipo.JOYSTICK, 0.15f, 0.65f, 120),
        Botao("K\nTiro", KeyEvent.KEYCODE_K, Tipo.SEGURAR, 0.85f, 0.55f, 68),
        Botao("Espaço\nPular", KeyEvent.KEYCODE_SPACE, Tipo.TOQUE, 0.92f, 0.40f, 68),
        Botao("C\nAgachar", KeyEvent.KEYCODE_C, Tipo.TOQUE, 0.92f, 0.70f, 68),
        Botao("R\nRecarga", KeyEvent.KEYCODE_R, Tipo.TOQUE, 0.75f, 0.80f, 68),
        Botao("E\nUsar", KeyEvent.KEYCODE_E, Tipo.TOQUE, 0.70f, 0.30f, 68),
        Botao("B\nComprar", KeyEvent.KEYCODE_B, Tipo.TOQUE, 0.50f, 0.10f, 68),
        Botao("1\nArma 1", KeyEvent.KEYCODE_1, Tipo.TOQUE, 0.60f, 0.90f, 68),
        Botao("2\nArma 2", KeyEvent.KEYCODE_2, Tipo.TOQUE, 0.67f, 0.90f, 68),
        Botao("3\nArma 3", KeyEvent.KEYCODE_3, Tipo.TOQUE, 0.74f, 0.90f, 68)
    )

    private val flagsBase = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var menu: TextView? = null
    private var modoEdicao = true
    private var criado = false

    private var cima = false
    private var baixo = false
    private var esq = false
    private var dir = false
    private var joyAtivo = false

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

        for (b in botoes) {
            val tam = (b.dp * d.density).toInt()
            val cor = if (b.tipo == Tipo.JOYSTICK) "#CC2E7D32" else "#CC1565C0"

            val tv = TextView(this).apply {
                text = b.texto
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(cor))
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
                x = prefs.getInt("k${b.id}_x", (larg * b.xPadrao).toInt() - tam / 2)
                y = prefs.getInt("k${b.id}_y", (alt * b.yPadrao).toInt() - tam / 2)
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
                            .putInt("k${b.id}_x", lp.x)
                            .putInt("k${b.id}_y", lp.y)
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
        if (modoEdicao) {
            soltarTudo()
        } else {
            val msg = Toques.preparar()
            if (msg != null) {
                Toast.makeText(this, "Mapeador: $msg", Toast.LENGTH_LONG).show()
            }
        }
        aplicarModo()
    }

    private fun soltarTudo() {
        cima = false
        baixo = false
        esq = false
        dir = false
        joyAtivo = false
        Toques.soltarTudo()
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
        val down = event.action == KeyEvent.ACTION_DOWN

        if (event.keyCode == KeyEvent.KEYCODE_F1) {
            if (down && event.repeatCount == 0) alternarModo()
            return true
        }
        if (modoEdicao) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_W -> { cima = down; atualizarJoystick(); return true }
            KeyEvent.KEYCODE_S -> { baixo = down; atualizarJoystick(); return true }
            KeyEvent.KEYCODE_A -> { esq = down; atualizarJoystick(); return true }
            KeyEvent.KEYCODE_D -> { dir = down; atualizarJoystick(); return true }
        }

        val b = botoes.firstOrNull { it.id == event.keyCode && it.tipo != Tipo.JOYSTICK }
            ?: return false
        if (event.repeatCount > 0) return true
        val c = centro(b) ?: return true

        if (b.tipo == Tipo.SEGURAR) {
            if (down) Toques.descer(b.id, c.first, c.second) else Toques.subir(b.id)
        } else if (down) {
            Toques.tocar(b.id, c.first, c.second)
        }
        return true
    }

    private fun centro(b: Botao): Pair<Float, Float>? {
        val v = b.view ?: return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Pair(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    private fun atualizarJoystick() {
        val j = botoes.first { it.tipo == Tipo.JOYSTICK }
        var dx = 0f
        var dy = 0f
        if (esq) dx -= 1f
        if (dir) dx += 1f
        if (cima) dy -= 1f
        if (baixo) dy += 1f

        if (dx == 0f && dy == 0f) {
            if (joyAtivo) {
                Toques.subir(JOY_ID)
                joyAtivo = false
            }
            return
        }

        val c = centro(j) ?: return
        val len = sqrt(dx * dx + dy * dy)
        val raio = (j.view?.width ?: 0) / 2f
        val tx = c.first + dx / len * raio
        val ty = c.second + dy / len * raio

        if (!joyAtivo) {
            Toques.descer(JOY_ID, c.first, c.second)
            joyAtivo = true
        }
        Toques.mover(JOY_ID, tx, ty)
    }

    override fun onDestroy() {
        if (criado) {
            Toques.soltarTudo()
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
