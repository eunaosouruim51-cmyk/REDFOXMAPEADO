package com.redfox.mapeador

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var alvo1: TextView
    private lateinit var alvo2: TextView
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
        val lp = LinearLayout.LayoutParams(0, (140 * d).toInt(), 1f).apply {
            setMargins(8, 16, 8, 16)
        }
        linha.addView(alvo1, lp)
        linha.addView(alvo2, lp)
        raiz.addView(linha)

        val b2 = Button(this).apply {
            text = "2. Testar 1 toque (Alvo 1)"
            setOnClickListener {
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
        }
        raiz.addView(b2)

        val b3 = Button(this).apply {
            text = "3. Testar 2 dedos juntos"
            setOnClickListener {
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
        }
        raiz.addView(b3)

        val b4 = Button(this).apply {
            text = "4. Abrir configurações de acessibilidade"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        raiz.addView(b4)

        setContentView(raiz)

        Shizuku.addRequestPermissionResultListener(aoResponderPermissao)
        Shizuku.addBinderReceivedListenerSticky(aoReceberBinder)
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
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(aoResponderPermissao)
        Shizuku.removeBinderReceivedListener(aoReceberBinder)
        super.onDestroy()
    }
}
