package org.woheller69.weather.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.woheller69.weather.R;
import org.woheller69.weather.activities.ForecastCityActivity;
import org.woheller69.weather.database.CurrentWeatherData;
import org.woheller69.weather.database.HourlyForecast;
import org.woheller69.weather.database.QuarterHourlyForecast;
import org.woheller69.weather.database.SQLiteHelper;
import org.woheller69.weather.database.WeekForecast;
import org.woheller69.weather.preferences.AppPreferencesManager;
import org.woheller69.weather.services.UpdateDataService;
import org.woheller69.weather.services.WidgetUpdater;
import org.woheller69.weather.ui.Help.StringFormatUtils;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static androidx.core.app.JobIntentService.enqueueWork;
import static org.woheller69.weather.database.SQLiteHelper.getWidgetCityID;
import static org.woheller69.weather.services.UpdateDataService.SKIP_UPDATE_INTERVAL;

public class WeatherSummaryBarWidget extends AppWidgetProvider {

    private static final long HOUR_MILLIS = 60L * 60L * 1000L;
    private static final double SYNODIC_MONTH_DAYS = 29.530588853;
    private static final double KNOWN_NEW_MOON_JULIAN_DAY = 2451550.25972;

    public void updateAppWidget(Context context, int appWidgetId) {
        SQLiteHelper db = SQLiteHelper.getInstance(context);
        if (db.getAllCitiesToWatch().isEmpty()) {
            return;
        }

        int cityId = getWidgetCityID(context);
        Intent intent = new Intent(context, UpdateDataService.class);
        intent.setAction(UpdateDataService.UPDATE_SINGLE_ACTION);
        intent.putExtra("cityId", cityId);
        intent.putExtra(SKIP_UPDATE_INTERVAL, true);
        enqueueWork(context, UpdateDataService.class, 0, intent);
    }

    public static void updateView(
            Context context,
            AppWidgetManager appWidgetManager,
            RemoteViews views,
            int appWidgetId,
            List<WeekForecast> weekForecasts,
            List<HourlyForecast> hourlyForecasts) {

        if (weekForecasts == null || weekForecasts.isEmpty()) {
            views.setTextViewText(R.id.weather_summary_top, context.getString(R.string.weather_summary_week_empty));
            views.setTextViewText(R.id.weather_summary_week, "");
            appWidgetManager.updateAppWidget(appWidgetId, views);
            return;
        }

        SQLiteHelper database = SQLiteHelper.getInstance(context);
        int cityId = getWidgetCityID(context);
        CurrentWeatherData currentWeather = database.getCurrentWeatherByCityId(cityId);
        int zoneSeconds = currentWeather.getTimeZoneSeconds();

        Float previousNightLow = previousNightLow(hourlyForecasts, weekForecasts, zoneSeconds);
        Float tonightLow = tonightLow(hourlyForecasts, weekForecasts, zoneSeconds);

        if (tonightLow == null) {
            if (weekForecasts.size() > 1) {
                tonightLow = weekForecasts.get(1).getMinTemperature();
            } else {
                tonightLow = weekForecasts.get(0).getMinTemperature();
            }
        }

        String moon = moonPhaseText(context, System.currentTimeMillis());
        String rain = nextRainText(context, database, cityId, hourlyForecasts, zoneSeconds);
        String todayHigh = compactTemperature(context, weekForecasts.get(0).getMaxTemperature(), true);
        String previousLow = previousNightLow == null
                ? "—"
                : compactTemperature(context, previousNightLow, true);
        String comingLow = compactTemperature(context, tonightLow, true);

        views.setTextViewText(
                R.id.weather_summary_top,
                context.getString(
                        R.string.weather_summary_bar_top,
                        moon,
                        rain,
                        todayHigh,
                        previousLow,
                        comingLow));

        views.setTextViewText(
                R.id.weather_summary_week,
                weekLine(context, weekForecasts, zoneSeconds));

        Intent intent = new Intent(context, ForecastCityActivity.class);
        intent.putExtra("cityId", cityId);

        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }

        views.setOnClickPendingIntent(R.id.weather_summary_bar_layout, pendingIntent);
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private static String nextRainText(
            Context context,
            SQLiteHelper database,
            int cityId,
            List<HourlyForecast> hourlyForecasts,
            int zoneSeconds) {

        long now = System.currentTimeMillis();
        long nowLocal = now + zoneSeconds * 1000L;

        List<QuarterHourlyForecast> quarterHourly =
                database.getQuarterHourlyForecastsByCityId(cityId);

        if (quarterHourly != null) {
            for (QuarterHourlyForecast forecast : quarterHourly) {
                if (forecast.getForecastTime() > now && forecast.getPrecipitation() > 0) {
                    if (forecast.getForecastTime() - now <= 30L * 60L * 1000L) {
                        return context.getString(R.string.weather_summary_rain_now);
                    }
                    return dayAndTime(
                            context,
                            forecast.getLocalForecastTime(context),
                            nowLocal);
                }
            }
        }

        if (hourlyForecasts != null) {
            for (HourlyForecast forecast : hourlyForecasts) {
                if (forecast.getForecastTime() > now && forecast.getPrecipitation() > 0) {
                    return dayAndTime(
                            context,
                            forecast.getForecastTime() + zoneSeconds * 1000L,
                            nowLocal);
                }
            }
        }

        return context.getString(R.string.weather_summary_no_rain);
    }

    private static String dayAndTime(Context context, long localTime, long nowLocal) {
        Calendar target = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        target.setTimeInMillis(localTime);

        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        now.setTimeInMillis(nowLocal);

        String time = StringFormatUtils.formatTimeWithoutZone(context, localTime);
        if (target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
            return time;
        }

        return context.getString(StringFormatUtils.getDayShort(target.get(Calendar.DAY_OF_WEEK)))
                + " " + time;
    }

    private static Float previousNightLow(
            List<HourlyForecast> hourlyForecasts,
            List<WeekForecast> weekForecasts,
            int zoneSeconds) {

        if (hourlyForecasts == null || hourlyForecasts.isEmpty()) {
            return null;
        }

        long zoneMillis = zoneSeconds * 1000L;
        long nowLocal = System.currentTimeMillis() + zoneMillis;
        long midnight = localMidnight(nowLocal);
        long start = midnight - 6L * HOUR_MILLIS;
        long end = midnight + 8L * HOUR_MILLIS;

        if (weekForecasts != null && !weekForecasts.isEmpty()) {
            long sunrise = weekForecasts.get(0).getTimeSunrise() * 1000L + zoneMillis;
            if (sunrise > midnight && sunrise < midnight + 12L * HOUR_MILLIS) {
                end = sunrise;
            }
        }

        if (nowLocal < end) {
            end = nowLocal;
        }

        return minTemperatureBetween(hourlyForecasts, zoneMillis, start, end);
    }

    private static Float tonightLow(
            List<HourlyForecast> hourlyForecasts,
            List<WeekForecast> weekForecasts,
            int zoneSeconds) {

        if (hourlyForecasts == null || hourlyForecasts.isEmpty()) {
            return null;
        }

        long zoneMillis = zoneSeconds * 1000L;
        long nowLocal = System.currentTimeMillis() + zoneMillis;
        long midnight = localMidnight(nowLocal);

        long start = midnight + 18L * HOUR_MILLIS;
        long end = midnight + 30L * HOUR_MILLIS;

        if (weekForecasts != null && !weekForecasts.isEmpty()) {
            long sunset = weekForecasts.get(0).getTimeSunset() * 1000L + zoneMillis;
            if (sunset > midnight && sunset < midnight + 24L * HOUR_MILLIS) {
                start = sunset;
            }

            if (weekForecasts.size() > 1) {
                long nextSunrise = weekForecasts.get(1).getTimeSunrise() * 1000L + zoneMillis;
                if (nextSunrise > start && nextSunrise < midnight + 40L * HOUR_MILLIS) {
                    end = nextSunrise;
                }
            }
        }

        return minTemperatureBetween(hourlyForecasts, zoneMillis, start, end);
    }

    private static Float minTemperatureBetween(
            List<HourlyForecast> hourlyForecasts,
            long zoneMillis,
            long startLocal,
            long endLocal) {

        Float minimum = null;

        for (HourlyForecast forecast : hourlyForecasts) {
            long localForecastTime = forecast.getForecastTime() + zoneMillis;
            if (localForecastTime >= startLocal && localForecastTime <= endLocal) {
                if (minimum == null || forecast.getTemperature() < minimum) {
                    minimum = forecast.getTemperature();
                }
            }
        }

        return minimum;
    }

    private static long localMidnight(long localTime) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        calendar.setTimeInMillis(localTime);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static String weekLine(
            Context context,
            List<WeekForecast> weekForecasts,
            int zoneSeconds) {

        if (weekForecasts.size() < 2) {
            return context.getString(R.string.weather_summary_week_empty);
        }

        StringBuilder line = new StringBuilder();
        int count = Math.min(weekForecasts.size(), 7);
        long zoneMillis = zoneSeconds * 1000L;

        for (int i = 1; i < count; i++) {
            WeekForecast forecast = weekForecasts.get(i);
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
            calendar.setTimeInMillis(forecast.getForecastTime() + zoneMillis);

            if (line.length() > 0) {
                line.append("  •  ");
            }

            line.append(context.getString(
                    StringFormatUtils.getDayShort(calendar.get(Calendar.DAY_OF_WEEK))));
            line.append(" ");
            line.append(compactTemperature(context, forecast.getMaxTemperature(), false));
            line.append("/");
            line.append(compactTemperature(context, forecast.getMinTemperature(), true));
        }

        return line.toString();
    }

    private static String compactTemperature(Context context, float celsius, boolean includeUnit) {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        AppPreferencesManager appPreferences =
                new AppPreferencesManager(preferences);

        float converted = appPreferences.convertTemperatureFromCelsius(celsius);
        String value = preferences.getBoolean("pref_TempDecimals", false)
                ? StringFormatUtils.formatDecimal(converted)
                : StringFormatUtils.formatInt(converted);

        return includeUnit ? value + appPreferences.getTemperatureUnit() : value;
    }

    private static String moonPhaseText(Context context, long timeMillis) {
        double julianDay = timeMillis / 86400000.0 + 2440587.5;
        double cycles = (julianDay - KNOWN_NEW_MOON_JULIAN_DAY) / SYNODIC_MONTH_DAYS;
        double fraction = cycles - Math.floor(cycles);
        int phase = ((int) Math.floor(fraction * 8.0 + 0.5)) % 8;

        switch (phase) {
            case 0:
                return "🌑 " + context.getString(R.string.moon_phase_new);
            case 1:
                return "🌒 " + context.getString(R.string.moon_phase_waxing_crescent);
            case 2:
                return "🌓 " + context.getString(R.string.moon_phase_first_quarter);
            case 3:
                return "🌔 " + context.getString(R.string.moon_phase_waxing_gibbous);
            case 4:
                return "🌕 " + context.getString(R.string.moon_phase_full);
            case 5:
                return "🌖 " + context.getString(R.string.moon_phase_waning_gibbous);
            case 6:
                return "🌗 " + context.getString(R.string.moon_phase_last_quarter);
            default:
                return "🌘 " + context.getString(R.string.moon_phase_waning_crescent);
        }
    }

    @Override
    public void onUpdate(
            Context context,
            AppWidgetManager appWidgetManager,
            int[] appWidgetIds) {

        PeriodicWorkRequest widgetUpdateRequest =
                new PeriodicWorkRequest.Builder(
                        WidgetUpdater.class,
                        6,
                        TimeUnit.HOURS)
                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "widgetUpdateWorkSummaryBar",
                ExistingPeriodicWorkPolicy.KEEP,
                widgetUpdateRequest);

        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetId);
        }
    }

    @Override
    public void onEnabled(Context context) {
        SQLiteHelper database = SQLiteHelper.getInstance(context);
        if (database.getAllCitiesToWatch().isEmpty()) {
            return;
        }

        int cityId = getWidgetCityID(context);
        List<WeekForecast> weekForecasts =
                database.getWeekForecastsByCityId(cityId);
        List<HourlyForecast> hourlyForecasts =
                database.getForecastsByCityId(cityId);

        int[] widgetIds = AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, WeatherSummaryBarWidget.class));

        for (int widgetId : widgetIds) {
            RemoteViews views =
                    new RemoteViews(context.getPackageName(), R.layout.weather_summary_bar_widget);
            updateView(
                    context,
                    AppWidgetManager.getInstance(context),
                    views,
                    widgetId,
                    weekForecasts,
                    hourlyForecasts);
        }
    }
}
