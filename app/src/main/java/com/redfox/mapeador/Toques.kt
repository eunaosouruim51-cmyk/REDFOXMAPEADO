package com.redfox.mapeador

import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

object Toques {

    private class Ponto(val chave: Int, val pid: Int, var x: Float, var y: Float)

    private val thread = HandlerThread("toques").apply { start() }
    private val handler = Handler(thread.looper)

    private val ativos = ArrayList<Ponto>()
    private var downTime = 0L

    private var manager: Any? = null
    private var metodo: Method? = null

    @Volatile
    var erro: String? = null

    @Synchronized
    fun preparar(): String? {
        if (manager != null && metodo != null) return null

        if (!Shizuku.pingBinder()) {
            return "O Shizuku não está rodando. Abra o Shizuku e toque em Iniciar."
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return "Falta a permissão do Shizuku. Toque no botão 1."
        }

        return try {
            HiddenApiBypass.addHiddenApiExemptions("L")
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
            val im = asInterface.invoke(null, binder)
            val iface = Class.forName("android.hardware.input.IInputManager")
            metodo = iface.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            manager = im
            null
        } catch (e: Throwable) {
            manager = null
            metodo = null
            "Falha ao preparar a injeção: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun enviar(acao: Int) {
        val n = ativos.size
        if (n == 0) return
        if (preparar() != null) return

        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = ativos[i].pid
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = ativos[i].x
                y = ativos[i].y
                pressure = 1f
                size = 1f
            }
        }
        val agora = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            downTime, agora, acao, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            metodo?.invoke(manager, ev, 0)
        } catch (e: Throwable) {
            erro = "Falha ao injetar: ${e.javaClass.simpleName}: ${e.cause?.message ?: e.message}"
            manager = null
            metodo = null
        } finally {
            ev.recycle()
        }
    }

    private fun liberar(idx: Int) {
        if (ativos.size == 1) {
            enviar(MotionEvent.ACTION_UP)
        } else {
            enviar(MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        }
        ativos.removeAt(idx)
    }

    fun descer(chave: Int, x: Float, y: Float) {
        handler.post {
            val existente = ativos.firstOrNull { it.chave == chave }
            if (existente != null) {
                existente.x = x
                existente.y = y
                enviar(MotionEvent.ACTION_MOVE)
                return@post
            }
            val pid = (0..9).first { p -> ativos.none { it.pid == p } }
            val primeiro = ativos.isEmpty()
            ativos.add(Ponto(chave, pid, x, y))
            if (primeiro) {
                downTime = SystemClock.uptimeMillis()
                enviar(MotionEvent.ACTION_DOWN)
            } else {
                val idx = ativos.size - 1
                enviar(MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            }
        }
    }

    fun mover(chave: Int, x: Float, y: Float) {
        handler.post {
            val p = ativos.firstOrNull { it.chave == chave } ?: return@post
            p.x = x
            p.y = y
            enviar(MotionEvent.ACTION_MOVE)
        }
    }

    fun subir(chave: Int) {
        handler.post {
            val idx = ativos.indexOfFirst { it.chave == chave }
            if (idx >= 0) liberar(idx)
        }
    }

    fun tocar(chave: Int, x: Float, y: Float) {
        descer(chave, x, y)
        handler.postDelayed({ subir(chave) }, 50)
    }

    fun soltarTudo() {
        handler.post {
            while (ativos.isNotEmpty()) liberar(ativos.size - 1)
        }
    }
}
