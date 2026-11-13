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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.sqrt

class TouchService : AccessibilityService() {

    private enum class Tipo { TOQUE, SEGURAR, JOYSTICK }

    private class Def(
        val id: Int,
        val nome: String,
        val rotulo: String,
        val tipo: Tipo,
        val xPadrao: Float,
        val yPadrao: Float,
        val dpPadrao: Int
    )

    private class Ativo(
        val def: Def,
        val view: TextView,
        val params: WindowManager.LayoutParams,
        val fundo: GradientDrawable,
        var dp: Int
    )

    private val JOY_ID = -1

    private val defs = listOf(
        Def(JOY_ID, "WASD - Andar (joystick)", "WASD\nAndar", Tipo.JOYSTICK, 0.15f, 0.65f, 120),
        Def(KeyEvent.KEYCODE_K, "K - Tiro (segura)", "K\nTiro", Tipo.SEGURAR, 0.85f, 0.55f, 68),
        Def(KeyEvent.KEYCODE_SPACE, "Espaço - Pular", "Espaço\nPular", Tipo.TOQUE, 0.92f, 0.40f, 64),
        Def(KeyEvent.KEYCODE_C, "C - Agachar", "C\nAgachar", Tipo.TOQUE, 0.92f, 0.70f, 64),
        Def(KeyEvent.KEYCODE_R, "R - Recarregar", "R\nRecarga", Tipo.TOQUE, 0.75f, 0.80f, 64),
        Def(KeyEvent.KEYCODE_E, "E - Usar", "E\nUsar", Tipo.TOQUE, 0.70f, 0.30f, 64),
        Def(KeyEvent.KEYCODE_B, "B - Comprar", "B\nComprar", Tipo.TOQUE, 0.50f, 0.10f, 64),
        Def(KeyEvent.KEYCODE_Q, "Q - Trocar arma", "Q\nArma", Tipo.TOQUE, 0.60f, 0.90f, 64)
    )

    private val flagsBase = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences

    private val ativos = LinkedHashMap<Int, Ativo>()
    private var selecionado: Ativo? = null
    private var barra: LinearLayout? = null
    private var btnModo: TextView? = null
    private var btnAdd: TextView? = null
    private var btnMenos: TextView? = null
    private var btnMais: TextView? = null
    private var btnRemover: TextView? = null
    private var painel: ScrollView? = null

    private var modoEdicao = false
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
        criarBarra()
        for (def in defs) {
            if (prefs.getBoolean("on_${def.id}", false)) adicionar(def)
        }
        aplicarModo()
        val msg = Toques.preparar()
        if (msg != null) {
            Toast.makeText(this, "Mapeador: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- interface ----------

    private fun pill(texto: String, acao: () -> Unit): TextView {
        return TextView(this).apply {
            text = texto
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(28, 12, 28, 12)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#CC000000"))
                setStroke(3, Color.WHITE)
            }
            setOnClickListener { acao() }
        }
    }

    private fun criarBarra() {
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mb = pill("EDITAR") { alternarModo() }
        val ma = pill("+ BOTÃO") { abrirPainel() }
        val mm = pill("−") { redimensionar(-8) }
        val mp = pill("+") { redimensionar(8) }
        val mr = pill("REMOVER") { remover() }
        for (v in listOf(mb, ma, mm, mp, mr)) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(6, 0, 6, 0) }
            b.addView(v, lp)
        }
        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flagsBase,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 8
        }
        wm.addView(b, wlp)
        barra = b
        btnModo = mb
        btnAdd = ma
        btnMenos = mm
        btnMais = mp
        btnRemover = mr
    }

    private fun atualizarBarra() {
        btnModo?.text = if (modoEdicao) "JOGAR" else "EDITAR"
        barra?.alpha = if (modoEdicao) 0.95f else 0.5f
        btnAdd?.visibility = if (modoEdicao) View.VISIBLE else View.GONE
        val vis = if (modoEdicao && selecionado != null) View.VISIBLE else View.GONE
        btnMenos?.visibility = vis
        btnMais?.visibility = vis
        btnRemover?.visibility = vis
    }

    private fun abrirPainel() {
        if (!modoEdicao) return
        fecharPainel()
        val dens = resources.displayMetrics.density
        val d = resources.displayMetrics
        val alt = minOf(d.widthPixels, d.heightPixels)

        val lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        fun item(texto: String, acao: () -> Unit): TextView {
            return TextView(this).apply {
                text = texto
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.parseColor("#FF37474F"))
                }
                setOnClickListener { acao() }
            }
        }

        fun addItem(v: View) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
            lista.addView(v, lp)
        }

        val titulo = TextView(this).apply {
            text = "Toque para colocar na tela"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        lista.addView(titulo)

        val livres = defs.filter { !ativos.containsKey(it.id) }
        if (livres.isEmpty()) {
            val vazio = TextView(this).apply {
                text = "Todos os botões já estão na tela."
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            }
            lista.addView(vazio)
        }
        for (def in livres) {
            addItem(item(def.nome) {
                adicionar(def)
                selecionar(ativos[def.id])
                fecharPainel()
            })
        }
        addItem(item("FECHAR") { fecharPainel() })

        val sv = ScrollView(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(Color.parseColor("#EE000000"))
                setStroke(3, Color.WHITE)
            }
            addView(lista)
        }
        val lp = WindowManager.LayoutParams(
            (270 * dens).toInt(),
            (alt * 0.7f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flagsBase,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        wm.addView(sv, lp)
        painel = sv
    }

    private fun fecharPainel() {
        painel?.let {
            try { wm.removeView(it) } catch (e: Exception) {}
        }
        painel = null
    }

    // ---------- botões ----------

    private fun tamTexto(dp: Int): Float = (dp / 6.5f).coerceIn(7f, 20f)

    private fun adicionar(def: Def) {
        if (ativos.containsKey(def.id)) return
        val d = resources.displayMetrics
        val larg = maxOf(d.widthPixels, d.heightPixels)
        val alt = minOf(d.widthPixels, d.heightPixels)
        val dp = prefs.getInt("s_${def.id}", def.dpPadrao)
        val tam = (dp * d.density).toInt()
        val cor = if (def.tipo == Tipo.JOYSTICK) "#CC2E7D32" else "#CC1565C0"

        val fundo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(cor))
            setStroke(3, Color.WHITE)
        }
        val tv = TextView(this).apply {
            text = def.rotulo
            setTextColor(Color.WHITE)
            textSize = tamTexto(dp)
            gravity = Gravity.CENTER
            background = fundo
        }
        val lp = WindowManager.LayoutParams(
            tam, tam,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flagsBase,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x_${def.id}", (larg * def.xPadrao).toInt() - tam / 2)
            y = prefs.getInt("y_${def.id}", (alt * def.yPadrao).toInt() - tam / 2)
        }
        val a = Ativo(def, tv, lp, fundo, dp)

        var iniX = 0
        var iniY = 0
        var toqueX = 0f
        var toqueY = 0f
        tv.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    selecionar(a)
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
                MotionEvent.ACTION_UP -> salvar(a)
            }
            true
        }

        wm.addView(tv, lp)
        ativos[def.id] = a
        salvar(a)
        aplicarEstado(a)
    }

    private fun salvar(a: Ativo) {
        prefs.edit()
            .putBoolean("on_${a.def.id}", true)
            .putInt("x_${a.def.id}", a.params.x)
            .putInt("y_${a.def.id}", a.params.y)
            .putInt("s_${a.def.id}", a.dp)
            .apply()
    }

    private fun selecionar(a: Ativo?) {
        selecionado?.let { it.fundo.setStroke(3, Color.WHITE) }
        selecionado = a
        a?.fundo?.setStroke(7, Color.YELLOW)
        atualizarBarra()
    }

    private fun redimensionar(delta: Int) {
        val a = selecionado ?: return
        val dens = resources.displayMetrics.density
        val novoDp = (a.dp + delta).coerceIn(32, 240)
        if (novoDp == a.dp) return
        val antigo = a.params.width
        val novo = (novoDp * dens).toInt()
        a.params.x += (antigo - novo) / 2
        a.params.y += (antigo - novo) / 2
        a.params.width = novo
        a.params.height = novo
        a.dp = novoDp
        a.view.textSize = tamTexto(novoDp)
        wm.updateViewLayout(a.view, a.params)
        salvar(a)
    }

    private fun remover() {
        val a = selecionado ?: return
        Toques.subir(a.def.id)
        if (a.def.id == JOY_ID) {
            joyAtivo = false
            cima = false
            baixo = false
            esq = false
            dir = false
        }
        try { wm.removeView(a.view) } catch (e: Exception) {}
        ativos.remove(a.def.id)
        prefs.edit().putBoolean("on_${a.def.id}", false).apply()
        selecionado = null
        atualizarBarra()
    }

    private fun aplicarEstado(a: Ativo) {
        if (modoEdicao) {
            a.params.flags = flagsBase
            a.view.alpha = 0.9f
        } else {
            a.params.flags = flagsBase or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            a.view.alpha = 0.3f
        }
        wm.updateViewLayout(a.view, a.params)
    }

    private fun aplicarModo() {
        for (a in ativos.values) aplicarEstado(a)
        if (!modoEdicao) {
            fecharPainel()
            selecionar(null)
        }
        atualizarBarra()
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

    // ---------- teclado ----------

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
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D -> {
                if (!ativos.containsKey(JOY_ID)) return false
                when (event.keyCode) {
                    KeyEvent.KEYCODE_W -> cima = down
                    KeyEvent.KEYCODE_S -> baixo = down
                    KeyEvent.KEYCODE_A -> esq = down
                    KeyEvent.KEYCODE_D -> dir = down
                }
                atualizarJoystick()
                return true
            }
        }

        val a = ativos[event.keyCode] ?: return false
        if (a.def.tipo == Tipo.JOYSTICK) return false
        if (event.repeatCount > 0) return true
        val c = centro(a)

        if (a.def.tipo == Tipo.SEGURAR) {
            if (down) Toques.descer(a.def.id, c.first, c.second) else Toques.subir(a.def.id)
        } else if (down) {
            Toques.tocar(a.def.id, c.first, c.second)
        }
        return true
    }

    private fun centro(a: Ativo): Pair<Float, Float> {
        val loc = IntArray(2)
        a.view.getLocationOnScreen(loc)
        return Pair(loc[0] + a.view.width / 2f, loc[1] + a.view.height / 2f)
    }

    private fun atualizarJoystick() {
        val j = ativos[JOY_ID] ?: return
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

        val c = centro(j)
        val len = sqrt(dx * dx + dy * dy)
        val raio = j.view.width / 2f
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
            fecharPainel()
            for (a in ativos.values) {
                try { wm.removeView(a.view) } catch (e: Exception) {}
            }
            ativos.clear()
            barra?.let {
                try { wm.removeView(it) } catch (e: Exception) {}
            }
            barra = null
            criado = false
        }
        super.onDestroy()
    }
}
