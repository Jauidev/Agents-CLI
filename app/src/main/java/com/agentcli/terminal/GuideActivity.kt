package com.agentcli.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agentcli.terminal.databinding.ActivityGuideBinding

/**
 * Guía de uso.
 *
 * La app esconde bastante maquinaria (un Linux entero, tmux, tres CLIs distintos)
 * detrás de una interfaz muy escueta, así que sin esto un usuario nuevo no tiene
 * de dónde agarrarse. Se abre desde el icono "?" de la pantalla de proyectos y,
 * una única vez, sola en el primer arranque.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding

    /** Cada apartado de la guía: título y explicación. */
    private val sections = listOf(
        R.string.guide_1_title to R.string.guide_1_body,
        R.string.guide_2_title to R.string.guide_2_body,
        R.string.guide_3_title to R.string.guide_3_body,
        R.string.guide_4_title to R.string.guide_4_body,
        R.string.guide_5_title to R.string.guide_5_body,
        R.string.guide_6_title to R.string.guide_6_body,
        R.string.guide_7_title to R.string.guide_7_body,
        R.string.guide_8_title to R.string.guide_8_body,
        R.string.guide_9_title to R.string.guide_9_body,
        R.string.guide_10_title to R.string.guide_10_body,
        R.string.guide_11_title to R.string.guide_11_body,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AgentTerminal)
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        sections.forEachIndexed { index, (title, body) ->
            binding.guideSections.addView(buildSection(index + 1, getString(title), getString(body)))
        }
        // Una guía tiene que empezar por el principio: sin esto el ScrollView
        // aparece desplazado al colocar los apartados.
        binding.guideScroll.post { binding.guideScroll.scrollTo(0, 0) }
        // A partir de aquí la guía ya no se abre sola.
        markSeen(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Un apartado: número en un círculo de acento + título + cuerpo. */
    private fun buildSection(number: Int, title: String, body: String): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(22) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = number.toString()
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@GuideActivity, R.color.bg_crust))
            background = ContextCompat.getDrawable(this@GuideActivity, R.drawable.dot)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@GuideActivity, R.color.accent),
            )
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
        })
        head.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@GuideActivity, R.color.text))
        })
        section.addView(head)

        section.addView(TextView(this).apply {
            text = body
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@GuideActivity, R.color.text_dim))
            setLineSpacing(0f, 1.3f)
            // El margen derecho evita que el texto quede pegado al borde: con la
            // sangría de la izquierda, sin él la columna se ve descuadrada.
            setPadding(dp(34), dp(6), dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        return section
    }

    companion object {
        private const val PREFS = "agentterminal"
        private const val KEY_SEEN = "guide_seen"

        /** ¿Es la primera vez que se abre la app? (para enseñar la guía sola). */
        fun shouldShowOnStart(ctx: Context): Boolean =
            !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

        fun markSeen(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SEEN, true).apply()
        }
    }
}
