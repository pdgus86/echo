package eu.mrogalski.saidit;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.Map;

/**
 * Resizable home-screen widget with Start, Pause, Stop, Save and Delete buttons.
 *
 * The buttons send actions to {@link SaidItService}, which does the actual work and then calls
 * {@link #setStatus(Context, String)} so every widget shows the current status.
 */
public class EchoWidgetProvider extends AppWidgetProvider {

    static final String STATUS_KEY = "widget_status";
    static final String STATUS_RECORDING = "recording";
    static final String STATUS_PAUSED = "paused";
    static final String STATUS_LISTENING = "listening";
    static final String STATUS_STOPPED = "stopped";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        updateAll(context, manager, appWidgetIds);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle newOptions) {
        // Redraw when resized (needed for the size-picking fallback on Android 11).
        updateAll(context, manager, new int[]{appWidgetId});
    }

    /** Called by the service whenever the recording state changes. */
    static void setStatus(Context context, String status) {
        context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                .edit().putString(STATUS_KEY, status).apply();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, EchoWidgetProvider.class));
        if (ids != null && ids.length > 0) {
            updateAll(context, manager, ids);
        }
    }

    private static void updateAll(Context context, AppWidgetManager manager, int[] ids) {
        String status = context.getSharedPreferences(SaidIt.PACKAGE_NAME, Context.MODE_PRIVATE)
                .getString(STATUS_KEY, STATUS_STOPPED);
        for (int id : ids) {
            manager.updateAppWidget(id, buildViews(context, manager, id, status));
        }
    }

    private static RemoteViews buildViews(Context context, AppWidgetManager manager, int id, String status) {
        RemoteViews compact = buildLayout(context, R.layout.widget_echo_compact, status);
        RemoteViews full = buildLayout(context, R.layout.widget_echo, status);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the launcher picks the layout that fits as the widget is resized.
            Map<SizeF, RemoteViews> sizes = new HashMap<>();
            sizes.put(new SizeF(40f, 40f), compact);
            sizes.put(new SizeF(260f, 40f), full);
            return new RemoteViews(sizes);
        }
        // Android 11: pick by the current width reported by the launcher.
        Bundle options = manager.getAppWidgetOptions(id);
        int minWidth = options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        return minWidth > 0 && minWidth < 260 ? compact : full;
    }

    private static RemoteViews buildLayout(Context context, int layout, String status) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setTextViewText(R.id.widget_status, context.getString(statusText(status)));
        views.setTextColor(R.id.widget_status, context.getColor(statusColor(status)));
        views.setOnClickPendingIntent(R.id.widget_start, action(context, SaidItService.ACTION_WIDGET_START, 1));
        views.setOnClickPendingIntent(R.id.widget_pause, action(context, SaidItService.ACTION_WIDGET_PAUSE, 2));
        views.setOnClickPendingIntent(R.id.widget_stop, action(context, SaidItService.ACTION_WIDGET_STOP, 3));
        views.setOnClickPendingIntent(R.id.widget_save, action(context, SaidItService.ACTION_WIDGET_SAVE, 4));
        views.setOnClickPendingIntent(R.id.widget_delete, action(context, SaidItService.ACTION_WIDGET_DELETE, 6));
        Intent open = new Intent(context, SaidItActivity.class);
        views.setOnClickPendingIntent(R.id.widget_status, PendingIntent.getActivity(
                context, 5, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return views;
    }

    private static PendingIntent action(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, SaidItService.class).setAction(action);
        return PendingIntent.getForegroundService(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static int statusText(String status) {
        switch (status) {
            case STATUS_RECORDING: return R.string.widget_status_recording;
            case STATUS_PAUSED: return R.string.widget_status_paused;
            case STATUS_LISTENING: return R.string.widget_status_listening;
            default: return R.string.widget_status_stopped;
        }
    }

    private static int statusColor(String status) {
        switch (status) {
            case STATUS_RECORDING: return R.color.light_red;
            case STATUS_PAUSED: return R.color.light_gold;
            case STATUS_LISTENING: return R.color.green;
            default: return R.color.gray_c;
        }
    }
}
