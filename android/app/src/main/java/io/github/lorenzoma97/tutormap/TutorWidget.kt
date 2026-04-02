package io.github.lorenzoma97.tutormap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Widget per la schermata home che mostra lo stato del monitoraggio
 * e permette di avviare/fermare il servizio con un singolo tocco.
 *
 * Layout fornito da R.layout.widget_tutor con i seguenti ID:
 * - widgetContainer: contenitore principale (apre l'app al tocco)
 * - widgetStatus: testo stato "Attivo" (verde) / "Pronto" (grigio)
 * - widgetButton: pulsante play/stop per il toggle del servizio
 */
class TutorWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Aggiorna tutti i widget quando riceve il broadcast di aggiornamento
        if (intent.action == "io.github.lorenzoma97.tutormap.WIDGET_UPDATE") {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TutorWidget::class.java))
            for (id in ids) {
                updateWidget(context, mgr, id)
            }
        }
    }

    companion object {
        /**
         * Aggiorna un singolo widget con lo stato corrente del servizio.
         * Configura il testo di stato, il colore, l'icona del pulsante
         * e i PendingIntent per il toggle e l'apertura dell'app.
         */
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_tutor)

            val running = LocationService.isRunning

            // Aggiorna testo e colore dello stato
            views.setTextViewText(R.id.widgetStatus, if (running) "Attivo" else "Pronto")
            views.setTextColor(
                R.id.widgetStatus,
                if (running) 0xFF34a853.toInt() else 0xFF999999.toInt()
            )

            // Aggiorna icona del pulsante play/stop
            views.setImageViewResource(
                R.id.widgetButton,
                if (running) R.drawable.ic_stop else R.drawable.ic_play
            )

            // Intent per avviare/fermare il monitoraggio
            val toggleIntent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_WIDGET_TOGGLE
            }
            val togglePending = PendingIntent.getService(
                context, 0, toggleIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetButton, togglePending)

            // Intent per aprire l'app al tocco sul contenitore
            val openIntent = Intent(context, MainActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context, 1, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetContainer, openPending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Aggiorna tutti i widget attivi dell'app.
         * Chiamato dal servizio quando cambia lo stato del monitoraggio.
         */
        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TutorWidget::class.java))
            for (id in ids) {
                updateWidget(context, mgr, id)
            }
        }
    }
}
