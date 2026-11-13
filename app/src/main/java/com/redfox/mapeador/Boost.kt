package com.redfox.mapeador

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display
import rikka.shizuku.Shizuku
import java.util.Locale

object Boost {

    private val ANIM = arrayOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale"
    )
    private val HZ = arrayOf("min_refresh_rate", "peak_refresh_rate")
    private val PACOTE_OK = Regex("[A-Za-z0-9._]+")
    private val VALOR_OK = Regex("[A-Za-z0-9._-]+")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("boost", Context.MODE_PRIVATE)

    fun ligado(ctx: Context): Boolean = sp(ctx).getBoolean("ligado", false)

    fun apps(ctx: Context): Set<String> =
        HashSet(sp(ctx).getStringSet("apps", emptySet()) ?: emptySet())

    fun salvarApps(ctx: Context, s: Set<String>) {
        sp(ctx).edit().putStringSet("apps", HashSet(s)).apply()
    }

    // ---------- modo automático ----------

    fun autoPkg(ctx: Context): String? = sp(ctx).getString("auto_pkg", null)

    fun salvarAutoPkg(ctx: Context, pkg: String?) {
        sp(ctx).edit().putString("auto_pkg", pkg).apply()
    }

    fun autoOn(ctx: Context): Boolean = sp(ctx).getBoolean("auto_on", false)

    fun salvarAutoOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("auto_on", v).apply()
    }

    fun ligouAuto(ctx: Context): Boolean = sp(ctx).getBoolean("auto_ligou", false)

    fun marcarLigouAuto(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("auto_ligou", v).apply()
    }

    fun maxHz(ctx: Context): Float {
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
            d.supportedModes.maxOf { it.refreshRate }
        } catch (e: Throwable) {
            60f
        }
    }

    // liga com as opções que estão marcadas na tela do app
    fun ligarSalvo(ctx: Context): String {
        val ui = ctx.getSharedPreferences("boost_ui", Context.MODE_PRIVATE)
        val hz = maxHz(ctx)
        val taxa = if (ui.getBoolean("taxa", true) && hz > 61f) hz else 0f
        return ligar(
            ctx,
            ui.getBoolean("fechar", true),
            ui.getBoolean("dnd", true),
            ui.getBoolean("anim", true),
            taxa
        )
    }

    // ---------- shizuku ----------

    private fun pronto(): String? {
        if (!Shizuku.pingBinder()) {
            return "O Shizuku não está rodando. Abra o Shizuku e toque em Iniciar."
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return "Falta a permissão do Shizuku. Toque em \"1. Pedir permissão\"."
        }
        return null
    }

    private fun shell(cmd: String): Pair<Int, String> {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val saida = p.inputStream.bufferedReader().readText()
            val erro = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            Pair(code, (saida + erro).trim())
        } catch (e: Throwable) {
            Pair(-1, "erro: ${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}")
        }
    }

    private fun obter(ns: String, chave: String): String {
        val (c, s) = shell("settings get $ns $chave")
        if (c != 0) return "null"
        val linha = s.lines().firstOrNull()?.trim()
        return if (linha.isNullOrEmpty()) "null" else linha
    }

    private fun colocar(ns: String, chave: String, valor: String) {
        shell("settings put $ns $chave $valor")
    }

    private fun restaurar(ns: String, chave: String, original: String?) {
        if (original == null || !VALOR_OK.matches(original) || original == "null") {
            shell("settings delete $ns $chave")
        } else {
            colocar(ns, chave, original)
        }
    }

    fun ligar(
        ctx: Context,
        fechar: Boolean,
        dnd: Boolean,
        anim: Boolean,
        taxaHz: Float
    ): String {
        val erro = pronto()
        if (erro != null) return "⚠️ $erro"

        val prefs = sp(ctx)
        val usaTaxa = taxaHz > 0f

        val ed = prefs.edit()
        if (dnd) ed.putString("o_zen", obter("global", "zen_mode"))
        if (anim) for (k in ANIM) ed.putString("o_$k", obter("global", k))
        if (usaTaxa) for (k in HZ) ed.putString("o_$k", obter("system", k))
        ed.putBoolean("a_dnd", dnd)
        ed.putBoolean("a_anim", anim)
        ed.putBoolean("a_taxa", usaTaxa)
        ed.putBoolean("ligado", true)
        ed.putBoolean("auto_ligou", false)
        ed.commit()

        val r = StringBuilder("✅ Modo jogo ligado\n")

        if (fechar) {
            val lista = apps(ctx)
            val jogo = autoPkg(ctx)
            var ok = 0
            for (pkg in lista) {
                if (!PACOTE_OK.matches(pkg)) continue
                if (pkg == jogo) continue
                val (c, _) = shell("am force-stop $pkg")
                if (c == 0) ok++
            }
            r.append("• Apps fechados: $ok de ${lista.size}\n")
        }

        if (dnd) {
            val (c, _) = shell("cmd notification set_dnd priority")
            if (c != 0) shell("settings put global zen_mode 1")
            val novo = obter("global", "zen_mode")
            if (novo == "1") {
                r.append("• Não perturbe: ligado\n")
            } else {
                r.append("• Não perturbe: o sistema não aceitou (valor $novo)\n")
            }
        }

        if (anim) {
            for (k in ANIM) colocar("global", k, "0")
            val ok = ANIM.all { obter("global", it).toFloatOrNull() == 0f }
            if (ok) {
                r.append("• Animações: desligadas\n")
            } else {
                r.append("• Animações: o sistema não aceitou\n")
            }
        }

        if (usaTaxa) {
            val v = String.format(Locale.US, "%.1f", taxaHz)
            for (k in HZ) colocar("system", k, v)
            val lido = obter("system", "peak_refresh_rate").toFloatOrNull()
            if (lido != null && lido >= taxaHz - 0.5f) {
                r.append("• Taxa de atualização: ${taxaHz.toInt()} Hz gravada (alguns aparelhos ignoram)\n")
            } else {
                r.append("• Taxa de atualização: o sistema não aceitou\n")
            }
        }

        r.append("\nPara voltar ao normal, toque em DESLIGAR.")
        return r.toString()
    }

    fun desligar(ctx: Context): String {
        val erro = pronto()
        if (erro != null) {
            return "⚠️ $erro\n(O modo jogo continua marcado como ligado. Inicie o Shizuku e tente de novo.)"
        }
        val prefs = sp(ctx)
        val r = StringBuilder("✅ Modo jogo desligado\n")

        if (prefs.getBoolean("a_dnd", false)) {
            val num = prefs.getString("o_zen", "0")?.toIntOrNull() ?: 0
            val modo = when (num) {
                1 -> "priority"
                2 -> "none"
                3 -> "alarms"
                else -> "off"
            }
            val (c, _) = shell("cmd notification set_dnd $modo")
            if (c != 0) shell("settings put global zen_mode $num")
            r.append("• Não perturbe: voltou ao que era\n")
        }
        if (prefs.getBoolean("a_anim", false)) {
            for (k in ANIM) restaurar("global", k, prefs.getString("o_$k", null))
            r.append("• Animações: restauradas\n")
        }
        if (prefs.getBoolean("a_taxa", false)) {
            for (k in HZ) restaurar("system", k, prefs.getString("o_$k", null))
            r.append("• Taxa de atualização: restaurada\n")
        }

        prefs.edit()
            .putBoolean("ligado", false)
            .putBoolean("auto_ligou", false)
            .apply()
        r.append("• Os apps que foram fechados abrem normalmente quando você tocar neles.")
        return r.toString()
    }
}
