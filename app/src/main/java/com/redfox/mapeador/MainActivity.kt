package com.redfox.mapeador

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var alvo1: TextView
    private lateinit var alvo2: TextView
    private lateinit var btnBoost: Button
    private lateinit var btnApps: Button
    private lateinit var logBoost: TextView
    private lateinit var cbFechar: CheckBox
    private lateinit var cbDnd: CheckBox
    private lateinit var cbAnim: CheckBox
    private lateinit var cbTaxa: CheckBox
    private var toques1 = 0
    private var toques2 = 0

    private val aoResponderPermissao =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { atualizar() } }
    private val aoReceberBinder =
        Shizuku.OnBinderReceivedListener { runOnUiThread { atualizar() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (32 * d).toInt(), pad, pad)
        }

        status = TextView(this).apply {
            textSize = 15f
            text = "Verificando..."
        }
        raiz.addView(status)

        val b1 = Button(this).apply {
            text = "1. Pedir permissão do Shizuku"
            setOnClickListener {
                if (!Shizuku.pingBinder()) {
                    atualizar()
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    atualizar()
                } else {
                    Shizuku.requestPermission(100)
                }
            }
        }
        raiz.addView(b1)

        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        alvo1 = criarAlvo("ALVO 1") { toques1++; toques1 }
        alvo2 = criarAlvo("ALVO 2") { toques2++; toques2 }
        val lp = LinearLayout.LayoutParams(0, (110 * d).toInt(), 1f).apply {
            setMargins(8, 16, 8, 16)
        }
        linha.addView(alvo1, lp)
        linha.addView(alvo2, lp)
        raiz.addView(linha)

        val b2 = Button(this).apply {
            text = "2. Testar 1 toque (Alvo 1)"
            setOnClickListener { testarUm() }
        }
        raiz.addView(b2)

        val b3 = Button(this).apply {
            text = "3. Testar 2 dedos juntos"
            setOnClickListener { testarDois() }
        }
        raiz.addView(b3)

        val b4 = Button(this).apply {
            text = "4. Abrir configurações de acessibilidade"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        raiz.addView(b4)

        // ---------- MODO JOGO ----------
        val titulo = TextView(this).apply {
            text = "MODO JOGO"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, (28 * d).toInt(), 0, (4 * d).toInt())
        }
        raiz.addView(titulo)

        cbFechar = caixa("Fechar apps em segundo plano", "fechar")
        raiz.addView(cbFechar)

        btnApps = Button(this).apply {
            setOnClickListener { escolherApps() }
        }
        raiz.addView(btnApps)

        cbDnd = caixa("Ligar o Não perturbe", "dnd")
        raiz.addView(cbDnd)

        cbAnim = caixa("Desligar as animações do sistema", "anim")
        raiz.addView(cbAnim)

        val hz = maxHz()
        cbTaxa = caixa(
            if (hz > 61f) "Travar a taxa de atualização em ${hz.toInt()} Hz"
            else "Taxa de atualização (a tela só tem 60 Hz)",
            "taxa"
        )
        if (hz <= 61f) {
            cbTaxa.isChecked = false
            cbTaxa.isEnabled = false
        }
        raiz.addView(cbTaxa)

        btnBoost = Button(this).apply {
            setOnClickListener { alternarBoost() }
        }
        raiz.addView(btnBoost)

        logBoost = TextView(this).apply {
            textSize = 14f
            setPadding(0, (8 * d).toInt(), 0, (24 * d).toInt())
        }
        raiz.addView(logBoost)

        val rolagem = ScrollView(this)
        rolagem.addView(raiz)
        setContentView(rolagem)

        atualizarBoost()
        Shizuku.addRequestPermissionResultListener(aoResponderPermissao)
        Shizuku.addBinderReceivedListenerSticky(aoReceberBinder)
    }

    // ---------- modo jogo ----------

    private fun caixa(texto: String, chave: String): CheckBox {
        val ui = getSharedPreferences("boost_ui", Context.MODE_PRIVATE)
        return CheckBox(this).apply {
            text = texto
            isChecked = ui.getBoolean(chave, true)
            setOnCheckedChangeListener { _, marcado ->
                ui.edit().putBoolean(chave, marcado).apply()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun maxHz(): Float {
        return try {
            windowManager.defaultDisplay.supportedModes.maxOf { it.refreshRate }
        } catch (e: Throwable) {
            60f
        }
    }

    private fun atualizarBoost() {
        btnBoost.text =
            if (Boost.ligado(this)) "DESLIGAR MODO JOGO" else "LIGAR MODO JOGO"
        btnApps.text = "Escolher apps para fechar (" + Boost.apps(this).size + ")"
    }

    private fun alternarBoost() {
        btnBoost.isEnabled = false
        logBoost.text = "Trabalhando..."
        val ctx = this@MainActivity
        val fechar = cbFechar.isChecked
        val dnd = cbDnd.isChecked
        val anim = cbAnim.isChecked
        val hz = maxHz()
        val taxa = if (cbTaxa.isChecked && hz > 61f) hz else 0f
        Thread {
            val msg = if (Boost.ligado(ctx)) {
                Boost.desligar(ctx)
            } else {
                Boost.ligar(ctx, fechar, dnd, anim, taxa)
            }
            runOnUiThread {
                logBoost.text = msg
                atualizarBoost()
                btnBoost.isEnabled = true
            }
        }.start()
    }

    private fun escolherApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, 0)
        val vistos = HashSet<String>()
        val lista = ArrayList<Pair<String, String>>()
        for (ri in infos) {
            val ai = ri.activityInfo.applicationInfo
            val pkg = ai.packageName
            if (pkg == packageName || pkg == "moe.shizuku.privileged.api") continue
            val sistema = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (sistema) continue
            if (!vistos.add(pkg)) continue
            lista.add(Pair(ri.loadLabel(pm).toString(), pkg))
        }
        lista.sortBy { it.first.lowercase() }

        if (lista.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Nenhum app encontrado")
                .setMessage("O Android não liberou a lista de apps. Instale a versão mais nova do Mapeador e tente de novo.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val escolhidos = Boost.apps(this).toMutableSet()
        val nomes = Array(lista.size) { lista[it].first }
        val marcados = BooleanArray(lista.size) { escolhidos.contains(lista[it].second) }

        AlertDialog.Builder(this)
            .setTitle("Apps para fechar (não marque o jogo!)")
            .setMultiChoiceItems(nomes, marcados) { _, i, marcado ->
                if (marcado) escolhidos.add(lista[i].second)
                else escolhidos.remove(lista[i].second)
            }
            .setPositiveButton("OK") { _, _ ->
                Boost.salvarApps(this@MainActivity, escolhidos)
                atualizarBoost()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- testes do Shizuku ----------

    private fun testarUm() {
        status.text = "Testando..."
        Toques.erro = null
        val msg = Toques.preparar()
        if (msg != null) {
            status.text = "⚠️ $msg"
        } else {
            val c = centro(alvo1)
            Toques.tocar(1, c.first, c.second)
            mostrarResultado()
        }
    }

    private fun testarDois() {
        status.text = "Testando..."
        Toques.erro = null
        val msg = Toques.preparar()
        if (msg != null) {
            status.text = "⚠️ $msg"
        } else {
            val c1 = centro(alvo1)
            val c2 = centro(alvo2)
            Toques.descer(1, c1.first, c1.second)
            Toques.descer(2, c2.first, c2.second)
            status.postDelayed({
                Toques.subir(1)
                Toques.subir(2)
            }, 150)
            mostrarResultado()
        }
    }

    private fun criarAlvo(nome: String, contar: () -> Int): TextView {
        return TextView(this).apply {
            text = "$nome\n0 toques"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#BBDEFB"))
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    val n = contar()
                    text = "$nome\n$n toques"
                }
                true
            }
        }
    }

    private fun centro(v: View): Pair<Float, Float> {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Pair(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    private fun mostrarResultado() {
        status.postDelayed({
            val e = Toques.erro
            status.text = if (e != null) {
                "⚠️ $e"
            } else {
                "✅ Toque enviado. Veja se os contadores subiram."
            }
        }, 600)
    }

    private fun atualizar() {
        val msg = Toques.preparar()
        status.text = if (msg == null) {
            "✅ Shizuku pronto: permissão concedida e injeção preparada."
        } else {
            "⚠️ $msg"
        }
    }

    override fun onResume() {
        super.onResume()
        atualizar()
        if (::btnBoost.isInitialized) atualizarBoost()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(aoResponderPermissao)
        Shizuku.removeBinderReceivedListener(aoReceberBinder)
        super.onDestroy()
    }
}
