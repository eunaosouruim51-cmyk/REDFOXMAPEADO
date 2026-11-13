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

    private enum class Tipo { TOQUE, SEGURAR, JOYSTICK, CAMERA, DESLIZAR }

    private class Def(
        val id: Int,
        val nome: String,
        val titulo: String,
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
    private val CAM_ID = -2
    private val SWIPE_ID = -3
    private val TIRO_ID = KeyEvent.KEYCODE_K
    private val IDLE_MS = 350L

    // botões especiais (só um de cada)
    private val especiais = listOf(
        Def(JOY_ID, "Andar (joystick)", "Andar", Tipo.JOYSTICK, 0.15f, 0.65f, 120),
        Def(CAM_ID, "Mouse - Câmera (área de giro)", "CÂMERA", Tipo.CAMERA, 0.62f, 0.35f, 220),
        Def(TIRO_ID, "Tiro (segura)", "Tiro", Tipo.SEGURAR, 0.85f, 0.55f, 68),
        Def(SWIPE_ID, "Trocar arma (deslizar)", "Arma", Tipo.DESLIZAR, 0.80f, 0.12f, 100)
    )

    // botões de tecla criados por você (quantos quiser)
    private val genericos = LinkedHashMap<Int, Def>()

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
    // cima, esquerda, baixo, direita
    private val jTeclas = intArrayOf(
        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D
    )
    private val sTeclas = IntArray(4)
    private val gNovo = IntArray(4)
    private var gEtapa = 0
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
    private var deslizando = false

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
        for (i in 0 until 4) {
            jTeclas[i] = prefs.getInt("jt_$i", jTeclas[i])
            sTeclas[i] = prefs.getInt("st_$i", 0)
        }

        val lista = prefs.getString("gen", "") ?: ""
        for (s in lista.split(",")) {
            val id = s.toIntOrNull() ?: continue
            genericos[id] = Def(id, "Botão de tecla", "", Tipo.TOQUE, 0.5f, 0.5f, 64)
        }
        migrar()

        criarBarra()
        for (def in especiais) {
            if (prefs.getBoolean("on_${def.id}", false)) adicionar(def)
        }
        for (def in genericos.values) adicionar(def)
        aplicarModo()
        val msg = Toques.preparar()
        if (msg != null) {
            Toast.makeText(this, "Mapeador: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- botões genéricos ----------

    private fun salvarListaGen() {
        prefs.edit().putString("gen", genericos.keys.joinToString(",")).apply()
    }

    private fun novoGenerico(): Def {
        val id = prefs.getInt("gen_next", 2000)
        prefs.edit().putInt("gen_next", id + 1).apply()
        val n = genericos.size
        val def = Def(
            id, "Botão de tecla", "", Tipo.TOQUE,
            0.30f + 0.05f * (n % 8), 0.25f + 0.07f * (n % 6), 64
        )
        genericos[id] = def
        salvarListaGen()
        return def
    }

    // traz os botões da versão anterior (pré-definidos) para botões de tecla
    private fun migrar() {
        if (prefs.getBoolean("mig2", false)) return
        val antigos = listOf(
            Pair(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_SPACE),
            Pair(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_C),
            Pair(KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_R),
            Pair(KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_E),
            Pair(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_B),
            Pair(1001, KeyEvent.KEYCODE_Q),
            Pair(1002, KeyEvent.KEYCODE_1),
            Pair(1003, KeyEvent.KEYCODE_2),
            Pair(1004, KeyEvent.KEYCODE_3)
        )
        for ((idAntigo, teclaPadrao) in antigos) {
            if (!prefs.getBoolean("on_$idAntigo", false)) continue
            val def = novoGenerico()
            val ed = prefs.edit()
            ed.putBoolean("on_${def.id}", true)
            ed.putInt("t_${def.id}", prefs.getInt("t_$idAntigo", teclaPadrao))
            if (prefs.contains("x_$idAntigo")) ed.putInt("x_${def.id}", prefs.getInt("x_$idAntigo", 0))
            if (prefs.contains("y_$idAntigo")) ed.putInt("y_${def.id}", prefs.getInt("y_$idAntigo", 0))
            if (prefs.contains("s_$idAntigo")) ed.putInt("s_${def.id}", prefs.getInt("s_$idAntigo", 64))
            ed.apply()
        }
        prefs.edit().putBoolean("mig2", true).apply()
    }

    // ---------- nomes de teclas ----------

    private fun nomeCurto(code: Int): String {
        if (code == 0) return "-"
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
            Tipo.DESLIZAR -> {
                val setas = arrayOf("↑", "←", "↓", "→")
                val partes = ArrayList<String>()
                for (i in 0 until 4) {
                    if (sTeclas[i] != 0) partes.add(setas[i] + nomeCurto(sTeclas[i]))
                }
                "Arma\n" + (if (partes.isEmpty()) "sem tecla" else partes.joinToString(" "))
            }
            else -> {
                val k = teclas[def.id] ?: 0
                val nome = if (k == 0) "?" else nomeCurto(k)
                if (def.titulo.isEmpty()) nome else nome + "\n" + def.titulo
            }
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

        fun item(texto: String, cor: String, acao: () -> Unit): TextView {
            return TextView(this).apply {
                text = texto
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.parseColor(cor))
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

        addItem(item("+ Botão de tecla (pode repetir)", "#FF1565C0") {
            val def = novoGenerico()
            adicionar(def)
            selecionar(ativos[def.id])
            fecharPainel()
            iniciarEspera()
        })

        for (def in especiais.filter { !ativos.containsKey(it.id) }) {
            addItem(item(def.nome, "#FF37474F") {
                adicionar(def)
                selecionar(ativos[def.id])
                fecharPainel()
                if (def.tipo == Tipo.DESLIZAR) iniciarEspera()
            })
        }
        addItem(item("FECHAR", "#FF37474F") { fecharPainel() })

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

    private fun tamTexto(dp: Int, tipo: Tipo): Float {
        val base = dp / 6.5f
        return (if (tipo == Tipo.DESLIZAR) base * 0.75f else base).coerceIn(7f, 20f)
    }

    private fun adicionar(def: Def) {
        if (ativos.containsKey(def.id)) return
        if (def.tipo == Tipo.TOQUE || def.tipo == Tipo.SEGURAR) {
            teclas[def.id] = prefs.getInt(
                "t_${def.id}",
                if (def.id == TIRO_ID) KeyEvent.KEYCODE_K else 0
            )
        }
        val d = resources.displayMetrics
        val larg = maxOf(d.widthPixels, d.heightPixels)
        val alt = minOf(d.widthPixels, d.heightPixels)
        val dp = prefs.getInt("s_${def.id}", def.dpPadrao)
        val tam = (dp * d.density).toInt()
        val cor = when (def.tipo) {
            Tipo.JOYSTICK -> "#CC2E7D32"
            Tipo.CAMERA -> "#996A1B9A"
            Tipo.DESLIZAR -> "#CCEF6C00"
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
            textSize = tamTexto(dp, def.tipo)
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
        a.view.textSize = tamTexto(novoDp, a.def.tipo)
        wm.updateViewLayout(a.view, a.params)
        salvar(a)
    }

    private fun remover() {
        val a = selecionado ?: return
        val id = a.def.id
        Toques.subir(id)
        if (esperando === a) esperando = null
        when (a.def.tipo) {
            Tipo.JOYSTICK -> {
                joyAtivo = false
                cima = false
                baixo = false
                esq = false
                dir = false
            }
            Tipo.CAMERA -> soltarCam()
            Tipo.DESLIZAR -> {
                deslizando = false
                Toques.subir(SWIPE_ID)
            }
            else -> {}
        }
        try { wm.removeView(a.view) } catch (e: Exception) {}
        ativos.remove(id)
        if (genericos.containsKey(id)) {
            genericos.remove(id)
            teclas.remove(id)
            prefs.edit()
                .remove("on_$id").remove("x_$id").remove("y_$id")
                .remove("s_$id").remove("t_$id")
                .apply()
            salvarListaGen()
        } else {
            prefs.edit().putBoolean("on_$id", false).apply()
        }
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
        deslizando = false
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
        gEtapa = 0
        atualizarBarra()
        if (a.def.tipo == Tipo.JOYSTICK || a.def.tipo == Tipo.DESLIZAR) {
            avisarEtapa(a)
        } else {
            Toast.makeText(
                this,
                "Aperte a tecla para este botão (Esc cancela)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun avisarEtapa(a: Ativo) {
        val nome = if (a.def.tipo == Tipo.JOYSTICK) "Andar" else "Arma (deslizar)"
        Toast.makeText(
            this,
            "$nome: tecla para ${nomesEtapa[gEtapa]} (${gEtapa + 1}/4). Backspace = sem tecla",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun emUso(tecla: Int, exceto: Int, ate: Int): String? {
        for (a in ativos.values) {
            if (a.def.id == exceto) continue
            when (a.def.tipo) {
                Tipo.TOQUE, Tipo.SEGURAR ->
                    if (teclas[a.def.id] == tecla) return "outro botão"
                Tipo.JOYSTICK ->
                    if (jTeclas.contains(tecla)) return "Andar"
                Tipo.DESLIZAR ->
                    if (sTeclas.contains(tecla)) return "Trocar arma"
                Tipo.CAMERA -> {}
            }
        }
        if (exceto == JOY_ID || exceto == SWIPE_ID) {
            for (i in 0 until ate) {
                if (gNovo[i] == tecla) return "esta mesma lista"
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
        if (a.def.tipo == Tipo.JOYSTICK || a.def.tipo == Tipo.DESLIZAR) {
            if (code == KeyEvent.KEYCODE_DEL) {
                gNovo[gEtapa] = 0
            } else {
                val c = emUso(code, a.def.id, gEtapa)
                if (c != null) {
                    Toast.makeText(this, "Essa tecla já é de: $c", Toast.LENGTH_SHORT).show()
                    return
                }
                gNovo[gEtapa] = code
            }
            gEtapa++
            if (gEtapa >= 4) {
                val ed = prefs.edit()
                for (i in 0 until 4) {
                    if (a.def.tipo == Tipo.JOYSTICK) {
                        jTeclas[i] = gNovo[i]
                        ed.putInt("jt_$i", gNovo[i])
                    } else {
                        sTeclas[i] = gNovo[i]
                        ed.putInt("st_$i", gNovo[i])
                    }
                }
                ed.apply()
                finalizarEspera(a)
            } else {
                avisarEtapa(a)
            }
        } else {
            val c = emUso(code, a.def.id, 0)
            if (c != null) {
                Toast.makeText(this, "Essa tecla já é de: $c", Toast.LENGTH_SHORT).show()
                return
            }
            teclas[a.def.id] = code
            prefs.edit().putInt("t_${a.def.id}", code).apply()
            finalizarEspera(a)
        }
    }

    // ---------- deslizar (troca de arma) ----------

    private fun deslizar(a: Ativo, direcao: Int) {
        if (deslizando) return
        deslizando = true
        val c = centro(a)
        val raio = a.view.width / 2f * 0.9f
        val dx = when (direcao) {
            1 -> -raio
            3 -> raio
            else -> 0f
        }
        val dy = when (direcao) {
            0 -> -raio
            2 -> raio
            else -> 0f
        }
        Toques.descer(SWIPE_ID, c.first, c.second)
        val passos = 6
        for (i in 1..passos) {
            handler.postDelayed({
                Toques.mover(SWIPE_ID, c.first + dx * i / passos, c.second + dy * i / passos)
            }, 25L * i)
        }
        handler.postDelayed({
            Toques.subir(SWIPE_ID)
            deslizando = false
        }, 25L * passos + 40L)
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
        if (event.keyCode == 0) return false

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

        val su = sTeclas.indexOf(event.keyCode)
        val arma = ativos[SWIPE_ID]
        if (su >= 0 && arma != null) {
            if (down && event.repeatCount == 0) deslizar(arma, su)
            return true
        }

        val a = ativos.values.firstOrNull {
            (it.def.tipo == Tipo.TOQUE || it.def.tipo == Tipo.SEGURAR) &&
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
