package com.redfox.mapeador

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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

    private enum class Tipo { TOQUE, SEGURAR, JOYSTICK, CAMERA }

    private class Def(
        val id: Int,
        val nome: String,
        val titulo: String,
        val tipo: Tipo,
        val xPadrao: Float,
        val yPadrao: Float,
        val dpPadrao: Int,
        val teclaPadrao: Int
    )

    private class Ativo(
        val def: Def,
        val view: TextView,
        val params: WindowManager.LayoutParams,
        val fundo: GradientDrawable,
        var dp: Int
    )

    private val JOY_ID = -1
    private val CAM_ID = -2
    private val TIRO_ID = KeyEvent.KEYCODE_K
    private val DROP_ID = 1001
    private val IDLE_MS = 350L

    // Os ids antigos (iguais aos keycodes) foram mantidos para não perder
    // as posições salvas. A tecla de cada botão agora é separada do id.
    private val defs = listOf(
        Def(JOY_ID, "Andar (joystick)", "Andar", Tipo.JOYSTICK, 0.15f, 0.65f, 120, 0),
        Def(CAM_ID, "Mouse - Câmera (área de giro)", "CÂMERA", Tipo.CAMERA, 0.62f, 0.35f, 220, 0),
        Def(TIRO_ID, "Tiro (segura)", "Tiro", Tipo.SEGURAR, 0.85f, 0.55f, 68, KeyEvent.KEYCODE_K),
        Def(KeyEvent.KEYCODE_SPACE, "Pular", "Pular", Tipo.TOQUE, 0.92f, 0.40f, 64, KeyEvent.KEYCODE_SPACE),
        Def(KeyEvent.KEYCODE_C, "Agachar", "Agachar", Tipo.TOQUE, 0.92f, 0.70f, 64, KeyEvent.KEYCODE_C),
        Def(KeyEvent.KEYCODE_R, "Recarregar", "Recarga", Tipo.TOQUE, 0.75f, 0.80f, 64, KeyEvent.KEYCODE_R),
        Def(KeyEvent.KEYCODE_E, "Usar", "Usar", Tipo.TOQUE, 0.70f, 0.30f, 64, KeyEvent.KEYCODE_E),
        Def(KeyEvent.KEYCODE_B, "Comprar", "Comprar", Tipo.TOQUE, 0.50f, 0.10f, 64, KeyEvent.KEYCODE_B),
        Def(KeyEvent.KEYCODE_Q, "Trocar arma", "Arma", Tipo.TOQUE, 0.60f, 0.90f, 64, KeyEvent.KEYCODE_X),
        Def(DROP_ID, "Dropar item", "Dropar", Tipo.TOQUE, 0.40f, 0.90f, 64, KeyEvent.KEYCODE_Q),
        Def(1002, "Botão extra 1", "Extra 1", Tipo.TOQUE, 0.30f, 0.20f, 64, KeyEvent.KEYCODE_1),
        Def(1003, "Botão extra 2", "Extra 2", Tipo.TOQUE, 0.36f, 0.20f, 64, KeyEvent.KEYCODE_2),
        Def(1004, "Botão extra 3", "Extra 3", Tipo.TOQUE, 0.42f, 0.20f, 64, KeyEvent.KEYCODE_3)
    )

    private val flagsBase = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private val ativos = LinkedHashMap<Int, Ativo>()
    private var selecionado: Ativo? = null

    // teclas
    private val teclas = HashMap<Int, Int>()
    private val jTeclas = intArrayOf(
        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D
    ) // cima, esquerda, baixo, direita
    private val jNovo = IntArray(4)
    private var jEtapa = 0
    private val nomesEtapa = arrayOf("CIMA", "ESQUERDA", "BAIXO", "DIREITA")
    private var esperando: Ativo? = null

    // barra
    private var barra: LinearLayout? = null
    private var linhaEdicao: LinearLayout? = null
    private var btnModo: TextView? = null
    private var btnAdd: TextView? = null
    private var btnMouse: TextView? = null
    private var lblSens: TextView? = null
    private var btnTecla: TextView? = null
    private var painel: ScrollView? = null

    private var modoEdicao = false
    private var criado = false

    private var cima = false
    private var baixo = false
    private var esq = false
    private var dir = false
    private var joyAtivo = false

    // mouse
    private var capView: View? = null
    private var capturando = false
    private var temCaptura = false
    private var botaoEsq = false
    private var camAtiva = false
    private var offX = 0f
    private var offY = 0f
    private var pendDx = 0f
    private var pendDy = 0f
    private var sens = 1.5f
    private val soltarCamRun = Runnable { soltarCam() }

    private val flushRun = object : Runnable {
        override fun run() {
            if (!capturando) return
            if (pendDx != 0f || pendDy != 0f) {
                val dx = pendDx
                val dy = pendDy
                pendDx = 0f
                pendDy = 0f
                moverCamera(dx, dy)
            }
            handler.postDelayed(this, 8)
        }
    }

    private inner class CapView(ctx: Context) : View(ctx) {
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && capturando) requestPointerCapture()
        }

        override fun onPointerCaptureChange(hasCapture: Boolean) {
            super.onPointerCaptureChange(hasCapture)
            temCaptura = hasCapture
            atualizarBarra()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (criado) return
        criado = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("mapeador", Context.MODE_PRIVATE)
        sens = prefs.getFloat("sens", 1.5f)
        for (d in defs) {
            teclas[d.id] = prefs.getInt("t_${d.id}", d.teclaPadrao)
        }
        for (i in 0 until 4) {
            jTeclas[i] = prefs.getInt("jt_$i", jTeclas[i])
        }
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

    // ---------- nomes de teclas ----------

    private fun nomeCurto(code: Int): String {
        return when (code) {
            KeyEvent.KEYCODE_SPACE -> "Espaço"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl"
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
            else -> {
                val s = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
                if (s.startsWith("NUMPAD_")) "N" + s.removePrefix("NUMPAD_") else s.take(6)
            }
        }
    }

    private fun rotulo(def: Def): String {
        return when (def.tipo) {
            Tipo.JOYSTICK -> "Andar\n" + jTeclas.joinToString("") { nomeCurto(it) }
            Tipo.CAMERA -> "CÂMERA"
            else -> nomeCurto(teclas[def.id] ?: def.teclaPadrao) + "\n" + def.titulo
        }
    }

    // ---------- interface ----------

    private fun pill(texto: String, acao: () -> Unit): TextView {
        return TextView(this).apply {
            text = texto
            textSize = 11f
            isSingleLine = true
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#CC000000"))
                setStroke(3, Color.WHITE)
            }
            setOnClickListener { acao() }
        }
    }

    private fun juntar(linha: LinearLayout, vararg vs: View) {
        for (v in vs) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(5, 3, 5, 3) }
            linha.addView(v, lp)
        }
    }

    private fun criarBarra() {
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val linha1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val linha2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val mb = pill("EDITAR") { alternarModo() }
        val ma = pill("+ BOTÃO") { abrirPainel() }
        val mo = pill("MOUSE: OFF") { alternarCaptura() }
        val sm = pill("SENS −") { ajustarSens(-0.25f) }
        val ls = pill("SENS 1.50") { }
        val sp = pill("SENS +") { ajustarSens(0.25f) }
        juntar(linha1, mb, ma, mo, sm, ls, sp)

        val mm = pill("−") { redimensionar(-8) }
        val mp = pill("+") { redimensionar(8) }
        val mt = pill("TECLA") { iniciarEspera() }
        val mr = pill("REMOVER") { remover() }
        juntar(linha2, mm, mp, mt, mr)

        coluna.addView(linha1)
        coluna.addView(linha2)

        val wlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flagsBase,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 4
        }
        wm.addView(coluna, wlp)
        barra = coluna
        linhaEdicao = linha2
        btnModo = mb
        btnAdd = ma
        btnMouse = mo
        lblSens = ls
        btnTecla = mt
    }

    private fun atualizarBarra() {
        btnModo?.text = if (modoEdicao) "JOGAR" else "EDITAR"
        barra?.alpha = if (modoEdicao) 0.95f else 0.5f
        btnAdd?.visibility = if (modoEdicao) View.VISIBLE else View.GONE
        btnMouse?.visibility = if (modoEdicao) View.GONE else View.VISIBLE
        lblSens?.text = "SENS " + "%.2f".format(sens)
        val sel = selecionado
        linhaEdicao?.visibility = if (modoEdicao && sel != null) View.VISIBLE else View.GONE
        btnTecla?.visibility =
            if (sel != null && sel.def.tipo != Tipo.CAMERA) View.VISIBLE else View.GONE
        btnTecla?.text = if (esperando != null) "APERTE A TECLA..." else "TECLA"
        btnMouse?.text = when {
            !capturando -> "MOUSE: OFF"
            temCaptura -> "MOUSE: ON"
            else -> "MOUSE: AGUARDA"
        }
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
        val cor = when (def.tipo) {
            Tipo.JOYSTICK -> "#CC2E7D32"
            Tipo.CAMERA -> "#996A1B9A"
            else -> "#CC1565C0"
        }

        val fundo = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(cor))
            setStroke(3, Color.WHITE)
        }
        val tv = TextView(this).apply {
            text = rotulo(def)
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
        if (esperando != null && esperando !== a) esperando = null
        selecionado?.let { it.fundo.setStroke(3, Color.WHITE) }
        selecionado = a
        a?.fundo?.setStroke(7, Color.YELLOW)
        atualizarBarra()
    }

    private fun redimensionar(delta: Int) {
        val a = selecionado ?: return
        val dens = resources.displayMetrics.density
        val novoDp = (a.dp + delta).coerceIn(32, 320)
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
        if (esperando === a) esperando = null
        if (a.def.id == JOY_ID) {
            joyAtivo = false
            cima = false
            baixo = false
            esq = false
            dir = false
        }
        if (a.def.id == CAM_ID) soltarCam()
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
            a.view.alpha = if (a.def.tipo == Tipo.CAMERA) 0.12f else 0.3f
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
        esperando = null
        if (modoEdicao) {
            desligarCaptura()
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
        soltarCam()
        Toques.soltarTudo()
    }

    private fun ajustarSens(delta: Float) {
        sens = (sens + delta).coerceIn(0.25f, 6f)
        prefs.edit().putFloat("sens", sens).apply()
        atualizarBarra()
    }

    // ---------- escolher tecla ----------

    private fun iniciarEspera() {
        val a = selecionado ?: return
        if (!modoEdicao || a.def.tipo == Tipo.CAMERA) return
        esperando = a
        jEtapa = 0
        atualizarBarra()
        if (a.def.tipo == Tipo.JOYSTICK) {
            avisarEtapa()
        } else {
            Toast.makeText(
                this,
                "Aperte a nova tecla para: ${a.def.titulo} (Esc cancela)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun avisarEtapa() {
        Toast.makeText(
            this,
            "Andar: aperte a tecla para ${nomesEtapa[jEtapa]} (${jEtapa + 1}/4)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun conflito(tecla: Int, exceto: Int, jNovoAte: Int): String? {
        for (d in defs) {
            if (d.tipo == Tipo.JOYSTICK || d.tipo == Tipo.CAMERA) continue
            if (d.id != exceto && teclas[d.id] == tecla) return d.titulo
        }
        if (exceto == JOY_ID) {
            for (i in 0 until jNovoAte) {
                if (jNovo[i] == tecla) return "Andar"
            }
        } else {
            for (t in jTeclas) {
                if (t == tecla) return "Andar"
            }
        }
        return null
    }

    private fun finalizarEspera(a: Ativo) {
        esperando = null
        a.view.text = rotulo(a.def)
        atualizarBarra()
    }

    private fun tratarEspera(code: Int) {
        val a = esperando ?: return
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            esperando = null
            atualizarBarra()
            Toast.makeText(this, "Cancelado", Toast.LENGTH_SHORT).show()
            return
        }
        if (a.def.tipo == Tipo.JOYSTICK) {
            val c = conflito(code, JOY_ID, jEtapa)
            if (c != null) {
                Toast.makeText(this, "Essa tecla já é de: $c", Toast.LENGTH_SHORT).show()
                return
            }
            jNovo[jEtapa] = code
            jEtapa++
            if (jEtapa >= 4) {
                val ed = prefs.edit()
                for (i in 0 until 4) {
                    jTeclas[i] = jNovo[i]
                    ed.putInt("jt_$i", jNovo[i])
                }
                ed.apply()
                finalizarEspera(a)
            } else {
                avisarEtapa()
            }
        } else {
            val c = conflito(code, a.def.id, 0)
            if (c != null) {
                Toast.makeText(this, "Essa tecla já é de: $c", Toast.LENGTH_SHORT).show()
                return
            }
            teclas[a.def.id] = code
            prefs.edit().putInt("t_${a.def.id}", code).apply()
            finalizarEspera(a)
        }
    }

    // ---------- mouse ----------

    private fun alternarCaptura() {
        if (modoEdicao) return
        if (capturando) desligarCaptura() else ligarCaptura()
    }

    private fun ligarCaptura() {
        if (capView != null) return
        capturando = true
        pendDx = 0f
        pendDy = 0f
        val v = CapView(this)
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.setOnCapturedPointerListener { _, ev ->
            tratarMouse(ev)
            true
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(v, lp)
        capView = v
        v.requestFocus()
        atualizarBarra()
        handler.removeCallbacks(flushRun)
        handler.post(flushRun)
        handler.postDelayed({ capView?.requestPointerCapture() }, 300)
        handler.postDelayed({
            if (capturando && !temCaptura) {
                Toast.makeText(
                    this,
                    "Mapeador: o Android não liberou a captura do mouse.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 1500)
    }

    private fun desligarCaptura() {
        capturando = false
        temCaptura = false
        handler.removeCallbacks(flushRun)
        pendDx = 0f
        pendDy = 0f
        soltarCam()
        if (botaoEsq) {
            botaoEsq = false
            atirarMouse(false)
        }
        capView?.let {
            try { it.releasePointerCapture() } catch (e: Exception) {}
            try { wm.removeView(it) } catch (e: Exception) {}
        }
        capView = null
        atualizarBarra()
    }

    private fun tratarMouse(ev: MotionEvent) {
        val esquerdo = (ev.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
        if (esquerdo != botaoEsq) {
            botaoEsq = esquerdo
            atirarMouse(esquerdo)
        }
        val acao = ev.actionMasked
        if (acao == MotionEvent.ACTION_MOVE || acao == MotionEvent.ACTION_HOVER_MOVE) {
            var dx = ev.x
            var dy = ev.y
            for (i in 0 until ev.historySize) {
                dx += ev.getHistoricalX(i)
                dy += ev.getHistoricalY(i)
            }
            pendDx += dx
            pendDy += dy
        }
    }

    private fun atirarMouse(down: Boolean) {
        val a = ativos[TIRO_ID] ?: return
        val c = centro(a)
        if (down) Toques.descer(TIRO_ID, c.first, c.second)
        else Toques.subir(TIRO_ID)
    }

    private fun moverCamera(dx: Float, dy: Float) {
        val cam = ativos[CAM_ID] ?: return
        val c = centro(cam)
        val raio = cam.view.width / 2f * 0.85f

        if (!camAtiva) {
            Toques.descer(CAM_ID, c.first, c.second)
            camAtiva = true
            offX = 0f
            offY = 0f
        }
        offX += dx * sens
        offY += dy * sens
        val len = sqrt(offX * offX + offY * offY)
        if (len > raio) {
            Toques.subir(CAM_ID)
            Toques.descer(CAM_ID, c.first, c.second)
            offX = 0f
            offY = 0f
        } else {
            Toques.mover(CAM_ID, c.first + offX, c.second + offY)
        }
        handler.removeCallbacks(soltarCamRun)
        handler.postDelayed(soltarCamRun, IDLE_MS)
    }

    private fun soltarCam() {
        handler.removeCallbacks(soltarCamRun)
        if (camAtiva) {
            Toques.subir(CAM_ID)
            camAtiva = false
        }
    }

    // ---------- teclado ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN

        when (event.keyCode) {
            KeyEvent.KEYCODE_F1 -> {
                if (down && event.repeatCount == 0) alternarModo()
                return true
            }
            KeyEvent.KEYCODE_F2 -> {
                if (down && event.repeatCount == 0) alternarCaptura()
                return true
            }
            KeyEvent.KEYCODE_F3 -> {
                if (down) ajustarSens(-0.25f)
                return true
            }
            KeyEvent.KEYCODE_F4 -> {
                if (down) ajustarSens(0.25f)
                return true
            }
        }

        if (esperando != null) {
            if (down && event.repeatCount == 0) tratarEspera(event.keyCode)
            return true
        }
        if (modoEdicao) return false

        val ju = jTeclas.indexOf(event.keyCode)
        if (ju >= 0 && ativos.containsKey(JOY_ID)) {
            when (ju) {
                0 -> cima = down
                1 -> esq = down
                2 -> baixo = down
                3 -> dir = down
            }
            atualizarJoystick()
            return true
        }

        val a = ativos.values.firstOrNull {
            it.def.tipo != Tipo.JOYSTICK &&
                    it.def.tipo != Tipo.CAMERA &&
                    teclas[it.def.id] == event.keyCode
        } ?: return false
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
            desligarCaptura()
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
