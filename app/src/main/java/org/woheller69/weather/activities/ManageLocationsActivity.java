package org.woheller69.weather.activities;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;

import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.woheller69.weather.R;
import org.woheller69.weather.database.City;
import org.woheller69.weather.database.CityToWatch;
import org.woheller69.weather.database.SQLiteHelper;
import org.woheller69.weather.dialogs.AddLocationDialogOmGeocodingAPI;
import org.woheller69.weather.ui.RecycleList.RecyclerItemClickListener;
import org.woheller69.weather.ui.RecycleList.RecyclerOverviewListAdapter;
import org.woheller69.weather.ui.RecycleList.SimpleItemTouchHelperCallback;
import org.woheller69.weather.ui.util.ThemeUtils;
import org.woheller69.weather.ui.viewPager.WeatherPagerAdapter;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

//in-App: where cities get added & sorted
public class ManageLocationsActivity extends NavigationActivity {

    private SQLiteHelper database;

    private ItemTouchHelper.Callback callback;
    private ItemTouchHelper touchHelper;
    RecyclerOverviewListAdapter adapter;
    List<CityToWatch> cities;
    Context context;
    private LocationManager locationManager;
    private LocationListener bootstrapLocationListener;
    private static final long RECENT_LOCATION_MAX_AGE_MILLIS = 15L * 60L * 1000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_locations);
        ThemeUtils.setStatusBarAppearance(this);
        context=this;
        database = SQLiteHelper.getInstance(getApplicationContext());


        try {
            cities = database.getAllCitiesToWatch();
            Collections.sort(cities, new Comparator<CityToWatch>() {
                @Override
                public int compare(CityToWatch o1, CityToWatch o2) {
                    return o1.getRank() - o2.getRank();
                }

            });
        } catch (NullPointerException e) {
            e.printStackTrace();
            Toast toast = Toast.makeText(getBaseContext(), "No cities in DB", Toast.LENGTH_SHORT);
            toast.show();
        }



        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.list_view_cities);
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(linearLayoutManager);

        recyclerView.addOnItemTouchListener(
                new RecyclerItemClickListener(getBaseContext(), recyclerView, new RecyclerItemClickListener.OnItemClickListener() {
                    @Override
                    public void onItemClick(View view, int position) {
                        AlertDialog.Builder alert = new AlertDialog.Builder(context);
                        final EditText edittext = new EditText(context);
                        edittext.setText(adapter.getCityName(position));
                        edittext.setTextSize(18);
                        edittext.setGravity(Gravity.CENTER);
                        alert.setTitle(getString(R.string.edit_location_hint_name));
                        alert.setView(edittext);

                        alert.setPositiveButton(getString(R.string.dialog_edit_change_button), (dialog, whichButton) -> adapter.renameCity(position, String.valueOf(edittext.getText())));
                        alert.setNegativeButton(getString(R.string.dialog_add_close_button), (dialog, whichButton) -> {
                        });

                        alert.show();
                    }

                    public void onLongItemClick(View view, int position) {

                    }

                })
        );

        adapter = new RecyclerOverviewListAdapter(getApplicationContext(), cities);
        recyclerView.setAdapter(adapter);
        recyclerView.setFocusable(false);

        callback = new SimpleItemTouchHelperCallback(adapter);
        touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(recyclerView);

        FloatingActionButton addFab1 = (FloatingActionButton) findViewById(R.id.fabAddLocation);

            if (addFab1 != null) {

                addFab1.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        FragmentManager fragmentManager = getSupportFragmentManager();
                        AddLocationDialogOmGeocodingAPI addLocationDialog = new AddLocationDialogOmGeocodingAPI();
                        addLocationDialog.show(fragmentManager, "AddLocationDialog");
                        getSupportFragmentManager().executePendingTransactions();
                    }
                });
            }

    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLocationHint();
        bootstrapGpsLocationIfNeeded();
    }

    @Override
    protected void onPause() {
        stopBootstrapLocationUpdates();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopBootstrapLocationUpdates();
        super.onDestroy();
    }

    @Override
    protected int getNavigationDrawerID() {
        return R.id.nav_manage;
    }

    public void addCityToList(City city) {
        CityToWatch newCity=convertCityToWatched(city);
        long id=database.addCityToWatch(newCity);
        newCity.setId((int) id);
        newCity.setCityId((int) id);  //use id also instead of city id as unique identifier
        cities.add(newCity);
        adapter.notifyDataSetChanged();
        updateLocationHint();
        WeatherPagerAdapter.refreshSingleData(getApplicationContext(), true, newCity.getCityId());
    }

    private void bootstrapGpsLocationIfNeeded() {
        if (cities == null || !cities.isEmpty()) return;

        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (!preferences.getBoolean("pref_GPS", false)) return;

        boolean coarseGranted =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        boolean fineGranted =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        if (!coarseGranted && !fineGranted) return;

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        String provider = chooseLocationProvider(fineGranted);
        if (provider == null) return;

        Location recent = getRecentLastKnownLocation(provider);
        if (recent != null) {
            addCurrentLocation(recent);
            return;
        }

        bootstrapLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                addCurrentLocation(location);
                stopBootstrapLocationUpdates();
            }

            @Deprecated
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        try {
            locationManager.requestLocationUpdates(
                    provider,
                    1000L,
                    0f,
                    bootstrapLocationListener,
                    Looper.getMainLooper());
        } catch (SecurityException ignored) {
            stopBootstrapLocationUpdates();
        }
    }

    private String chooseLocationProvider(boolean fineGranted) {
        try {
            if (fineGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Location getRecentLastKnownLocation(String provider) {
        try {
            Location location = locationManager.getLastKnownLocation(provider);
            if (location == null) return null;
            if (location.getTime() <= 0) return null;
            if (System.currentTimeMillis() - location.getTime() > RECENT_LOCATION_MAX_AGE_MILLIS) {
                return null;
            }
            return location;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private void addCurrentLocation(Location location) {
        if (location == null || cities == null || !cities.isEmpty()) return;

        City city = new City();
        city.setLatitude((float) location.getLatitude());
        city.setLongitude((float) location.getLongitude());
        city.setCityName(String.format(
                java.util.Locale.getDefault(),
                "%.2f° / %.2f°",
                location.getLatitude(),
                location.getLongitude()));
        addCityToList(city);
    }

    private void stopBootstrapLocationUpdates() {
        if (locationManager != null && bootstrapLocationListener != null) {
            try {
                locationManager.removeUpdates(bootstrapLocationListener);
            } catch (SecurityException ignored) {
            }
        }
        bootstrapLocationListener = null;
    }

    private void updateLocationHint() {
        TextView hint = findViewById(R.id.textView);
        if (hint == null) return;

        if (cities != null && cities.isEmpty()) {
            SharedPreferences preferences =
                    PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            hint.setText(preferences.getBoolean("pref_GPS", false)
                    ? R.string.manage_locations_waiting_gps
                    : R.string.manage_locations_empty);
        } else {
            hint.setText(R.string.long_press_text);
        }
    }

    private CityToWatch convertCityToWatched(City selectedCity) {

        return new CityToWatch(
                database.getMaxRank() + 1,
                -1,
                selectedCity.getCityId(), selectedCity.getLongitude(),selectedCity.getLatitude(),
                selectedCity.getCityName()
        );
    }
}
