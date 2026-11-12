package com.redfox.mapeador

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val texto = TextView(this).apply {
            text = "Mapeador\n\n1. Toque no botão abaixo.\n2. Ative o Mapeador em Acessibilidade.\n3. Com um teclado conectado, aperte a tecla F: o app toca no centro da tela."
            textSize = 16f
        }

        val botao = Button(this).apply {
            text = "Abrir configurações de acessibilidade"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(texto)
        layout.addView(botao)
        setContentView(layout)
    }
}
