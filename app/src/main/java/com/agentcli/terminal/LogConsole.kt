package com.agentcli.terminal

import android.app.Activity
import android.widget.TextView

/**
 * Consola de log en vivo sobre un [TextView], para las tareas largas del guest
 * (instalar el entorno, apt, npm…).
 *
 * Dos precauciones que hacen falta de verdad: solo se conserva la COLA del log
 * (pintar miles de líneas en un TextView congela el hilo de UI por el relayout
 * O(n²)) y la UI se refresca como mucho una de cada [REFRESH_EVERY] líneas.
 *
 * [append] es seguro desde cualquier hilo: es lo que reciben los callbacks de
 * `LinuxEngine.runInGuestStreaming`.
 */
class LogConsole(private val activity: Activity, private val view: TextView) {

    private val tail = ArrayDeque<String>()
    private var received = 0

    fun clear() {
        synchronized(tail) { tail.clear() }
        received = 0
        activity.runOnUiThread { view.text = "" }
    }

    /** Añade una línea al log; refresca la UI de vez en cuando. */
    fun append(line: String) {
        synchronized(tail) {
            tail.addLast(line)
            while (tail.size > MAX_LINES) tail.removeFirst()
        }
        if (received++ % REFRESH_EVERY == 0) flush()
    }

    /** Vuelca lo acumulado a la UI. Llamar al terminar una tarea. */
    fun flush() {
        val snapshot = text()
        activity.runOnUiThread { view.text = snapshot }
    }

    /** La cola actual del log como texto (para mensajes de error). */
    fun text(): String = synchronized(tail) { tail.joinToString("\n") }

    private companion object {
        const val MAX_LINES = 120
        const val REFRESH_EVERY = 4
    }
}
