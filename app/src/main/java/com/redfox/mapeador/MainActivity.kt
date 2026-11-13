package com.redfox.mapeador

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private val REQ_EXPORT = 201
    private val REQ_IMPORT = 202
    private val ID_OK = Regex("[A-Za-z0-9_]+")

    private lateinit var status: TextView
    private lateinit var alvo1: TextView
    private lateinit var alvo2: TextView
    private lateinit var btnBoost: Button
    private lateinit var btnApps: Button
    private lateinit var btnJogo: Button
    private lateinit var logBoost: TextView
    private lateinit var cbFechar: CheckBox
    private lateinit var cbDnd: CheckBox
    private lateinit var cbAnim: CheckBox
    private lateinit var cbTaxa: CheckBox
    private lateinit var cbAuto: CheckBox
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

        raiz.addView(Button(this).apply {
            text = "2. Testar 1 toque (Alvo 1)"
            setOnClickListener { testarUm() }
        })

        raiz.addView(Button(this).apply {
            text = "3. Testar 2 dedos juntos"
            setOnClickListener { testarDois() }
        })

        raiz.addView(Button(this).apply {
            text = "4. Abrir configurações de acessibilidade"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        // ---------- MODO JOGO ----------
        raiz.addView(titulo("MODO JOGO", d))

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

        val hz = Boost.maxHz(this)
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
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        raiz.addView(logBoost)

        // ---------- AUTOMÁTICO ----------
        raiz.addView(titulo("MODO JOGO AUTOMÁTICO", d))

        raiz.addView(TextView(this).apply {
            textSize = 13f
            text = "Liga sozinho quando você abre o jogo e desliga quando volta para a tela inicial. " +
                    "Precisa do Mapeador ligado em Acessibilidade e do Shizuku rodando."
        })

        btnJogo = Button(this).apply {
            setOnClickListener { escolherJogo() }
        }
        raiz.addView(btnJogo)

        cbAuto = CheckBox(this).apply {
            text = "Ligar e desligar sozinho com o jogo"
            isChecked = Boost.autoOn(this@MainActivity) && Boost.autoPkg(this@MainActivity) != null
            setOnCheckedChangeListener { _, marcado ->
                if (marcado && Boost.autoPkg(this@MainActivity) == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Escolha o jogo primeiro.",
                        Toast.LENGTH_SHORT
                    ).show()
                    isChecked = false
                } else {
                    Boost.salvarAutoOn(this@MainActivity, marcado)
                }
            }
        }
        raiz.addView(cbAuto)

        // ---------- PERFIS E BACKUP ----------
        raiz.addView(titulo("PERFIS E BACKUP", d))

        raiz.addView(Button(this).apply {
            text = "Renomear um perfil"
            setOnClickListener { renomearPerfil() }
        })
        raiz.addView(Button(this).apply {
            text = "Exportar backup (salvar em arquivo)"
            setOnClickListener { exportar() }
        })
        raiz.addView(Button(this).apply {
            text = "Importar backup (de um arquivo)"
            setOnClickListener { importar() }
        })
        raiz.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, (8 * d).toInt(), 0, (32 * d).toInt())
            text = "O backup guarda os perfis (botões, teclas, sensibilidade, transparência) " +
                    "e as escolhas do modo jogo. Importar substitui tudo o que existe hoje."
        })

        val rolagem = ScrollView(this)
        rolagem.addView(raiz)
        setContentView(rolagem)

        atualizarBoost()
        Shizuku.addRequestPermissionResultListener(aoResponderPermissao)
        Shizuku.addBinderReceivedListenerSticky(aoReceberBinder)
    }

    private fun titulo(texto: String, d: Float): TextView {
        return TextView(this).apply {
            text = texto
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, (28 * d).toInt(), 0, (4 * d).toInt())
        }
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

    private fun atualizarBoost() {
        btnBoost.text =
            if (Boost.ligado(this)) "DESLIGAR MODO JOGO" else "LIGAR MODO JOGO"
        btnApps.text = "Escolher apps para fechar (" + Boost.apps(this).size + ")"
        val pkg = Boost.autoPkg(this)
        btnJogo.text = "Escolher o jogo: " + (if (pkg == null) "(nenhum)" else nomeApp(pkg))
    }

    private fun nomeApp(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun alternarBoost() {
        btnBoost.isEnabled = false
        logBoost.text = "Trabalhando..."
        val ctx = this@MainActivity
        val fechar = cbFechar.isChecked
        val dnd = cbDnd.isChecked
        val anim = cbAnim.isChecked
        val hz = Boost.maxHz(this)
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

    private fun appsInstalados(): List<Pair<String, String>> {
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
        return lista
    }

    private fun avisoSemApps() {
        AlertDialog.Builder(this)
            .setTitle("Nenhum app encontrado")
            .setMessage("O Android não liberou a lista de apps. Instale a versão mais nova do Mapeador e tente de novo.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun escolherApps() {
        val lista = appsInstalados()
        if (lista.isEmpty()) {
            avisoSemApps()
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

    private fun escolherJogo() {
        val lista = appsInstalados()
        if (lista.isEmpty()) {
            avisoSemApps()
            return
        }
        val atual = Boost.autoPkg(this)
        val nomes = Array(lista.size + 1) { if (it == 0) "(nenhum)" else lista[it - 1].first }
        var indice = 0
        for (i in lista.indices) {
            if (lista[i].second == atual) indice = i + 1
        }

        AlertDialog.Builder(this)
            .setTitle("Qual é o jogo?")
            .setSingleChoiceItems(nomes, indice) { dialog, i ->
                if (i == 0) {
                    Boost.salvarAutoPkg(this@MainActivity, null)
                    Boost.salvarAutoOn(this@MainActivity, false)
                    cbAuto.isChecked = false
                } else {
                    Boost.salvarAutoPkg(this@MainActivity, lista[i - 1].second)
                }
                atualizarBoost()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- perfis ----------

    private fun meta(): SharedPreferences = getSharedPreferences("meta", Context.MODE_PRIVATE)

    private fun idsPerfis(): List<String> {
        val l = (meta().getString("perfis", "") ?: "").split(",").filter { it.isNotEmpty() }
        return if (l.isEmpty()) listOf("mapeador") else l
    }

    private fun nomePerfil(id: String): String =
        meta().getString("nome_$id", null) ?: if (id == "mapeador") "Padrão" else id

    private fun renomearPerfil() {
        val ids = idsPerfis()
        val nomes = Array(ids.size) { nomePerfil(ids[it]) }
        AlertDialog.Builder(this)
            .setTitle("Qual perfil renomear?")
            .setItems(nomes) { _, i -> pedirNome(ids[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirNome(id: String) {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(24))
            setText(nomePerfil(id))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Novo nome do perfil")
            .setView(campo)
            .setPositiveButton("Salvar") { _, _ ->
                val n = campo.text.toString().trim()
                if (n.isNotEmpty()) {
                    meta().edit().putString("nome_$id", n).commit()
                    TouchService.instance?.recarregar()
                    Toast.makeText(this, "Perfil renomeado", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- backup ----------

    private fun prefsParaJson(sp: SharedPreferences, permitidas: Set<String>?): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            if (permitidas != null && !permitidas.contains(k)) continue
            val e = JSONObject()
            when (v) {
                is Boolean -> { e.put("t", "b"); e.put("v", v) }
                is Int -> { e.put("t", "i"); e.put("v", v) }
                is Long -> { e.put("t", "l"); e.put("v", v) }
                is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                is String -> { e.put("t", "s"); e.put("v", v) }
                is Set<*> -> { e.put("t", "S"); e.put("v", JSONArray(v.map { it.toString() })) }
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    private fun aplicarJson(ed: SharedPreferences.Editor, o: JSONObject, permitidas: Set<String>?) {
        val chaves = o.keys()
        while (chaves.hasNext()) {
            val k = chaves.next()
            if (permitidas != null && !permitidas.contains(k)) continue
            val e = o.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "S" -> {
                    val a = e.getJSONArray("v")
                    val s = HashSet<String>()
                    for (i in 0 until a.length()) s.add(a.getString(i))
                    ed.putStringSet(k, s)
                }
            }
        }
    }

    private val BOOST_OK = setOf("apps", "auto_pkg", "auto_on")
    private val BOOST_UI_OK = setOf("fechar", "dnd", "anim", "taxa")

    private fun montarBackup(): String {
        val raiz = JSONObject()
        raiz.put("app", "mapeador")
        raiz.put("versao", 1)
        raiz.put("meta", prefsParaJson(meta(), null))
        val perfis = JSONObject()
        for (id in idsPerfis()) {
            perfis.put(id, prefsParaJson(getSharedPreferences(id, Context.MODE_PRIVATE), null))
        }
        raiz.put("perfis", perfis)
        raiz.put("boost", prefsParaJson(getSharedPreferences("boost", Context.MODE_PRIVATE), BOOST_OK))
        raiz.put("boost_ui", prefsParaJson(getSharedPreferences("boost_ui", Context.MODE_PRIVATE), BOOST_UI_OK))
        return raiz.toString(2)
    }

    private fun exportar() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "mapeador-backup.json")
        }
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importar() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQ_EXPORT) gravarBackup(uri)
        if (requestCode == REQ_IMPORT) lerBackup(uri)
    }

    private fun gravarBackup(uri: Uri) {
        try {
            val json = montarBackup()
            val saida = contentResolver.openOutputStream(uri, "wt")
            if (saida == null) {
                Toast.makeText(this, "Não consegui abrir o arquivo para gravar.", Toast.LENGTH_LONG).show()
                return
            }
            saida.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "✅ Backup salvo.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao salvar o backup: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun lerBackup(uri: Uri) {
        val texto = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        if (texto == null || texto.length > 2_000_000) {
            Toast.makeText(this, "Não consegui ler esse arquivo.", Toast.LENGTH_LONG).show()
            return
        }
        val raiz = try { JSONObject(texto) } catch (e: Exception) { null }
        val perfis = raiz?.optJSONObject("perfis")
        if (raiz == null || raiz.optString("app") != "mapeador" || perfis == null || perfis.length() == 0) {
            Toast.makeText(this, "Esse arquivo não é um backup do Mapeador.", Toast.LENGTH_LONG).show()
            return
        }
        val ids = ArrayList<String>()
        val chaves = perfis.keys()
        while (chaves.hasNext()) {
            val id = chaves.next()
            if (!ID_OK.matches(id) || perfis.optJSONObject(id) == null) {
                Toast.makeText(this, "O backup está com defeito.", Toast.LENGTH_LONG).show()
                return
            }
            ids.add(id)
        }

        AlertDialog.Builder(this)
            .setTitle("Importar backup?")
            .setMessage(
                "O backup tem ${ids.size} perfil(is). Importar SUBSTITUI todos os perfis, " +
                        "botões e escolhas que existem hoje."
            )
            .setPositiveButton("Importar") { _, _ -> aplicarBackup(raiz, ids) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun aplicarBackup(raiz: JSONObject, ids: List<String>) {
        try {
            val perfis = raiz.getJSONObject("perfis")

            for (id in idsPerfis()) {
                if (!ids.contains(id)) {
                    getSharedPreferences(id, Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
            for (id in ids) {
                val ed = getSharedPreferences(id, Context.MODE_PRIVATE).edit().clear()
                aplicarJson(ed, perfis.getJSONObject(id), null)
                ed.commit()
            }

            val metaJson = raiz.optJSONObject("meta")
            val m = meta().edit().clear()
            if (metaJson != null) aplicarJson(m, metaJson, null)
            m.putString("perfis", ids.joinToString(","))
            val atual = metaJson?.optJSONObject("atual")?.optString("v")
            if (atual == null || !ids.contains(atual)) m.putString("atual", ids[0])
            m.commit()

            raiz.optJSONObject("boost")?.let {
                val ed = getSharedPreferences("boost", Context.MODE_PRIVATE).edit()
                aplicarJson(ed, it, BOOST_OK)
                ed.commit()
            }
            raiz.optJSONObject("boost_ui")?.let {
                val ed = getSharedPreferences("boost_ui", Context.MODE_PRIVATE).edit()
                aplicarJson(ed, it, BOOST_UI_OK)
                ed.commit()
            }

            TouchService.instance?.recarregar()
            Toast.makeText(this, "✅ Backup importado.", Toast.LENGTH_LONG).show()
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao importar: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
