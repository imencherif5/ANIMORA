package com.example.animora;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapsActivity extends AppCompatActivity {

    // ====== A MODIFIER : IP du PC qui fait tourner Mosquitto / Node-RED ======
    private static final String BROKER_URI = "tcp://10.244.236.159:1883";
    private static final String MQTT_USER = "";   // laisser vide si anonymous
    private static final String MQTT_PASS = "";
    private static final String TOPIC_GPS = "animora/gps";

    // Si aucun message depuis ce delai (ms) -> "pas de donnees"
    private static final long DATA_TIMEOUT_MS = 10000;

    private TextView tvLatitude, tvLongitude, tvAltitude,
            tvLastUpdate, tvCity, tvStatus;
    private Button btnRefresh, btnOpenMaps;

    private MqttAsyncClient client;

    private final ExecutorService mqttExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService geocoderExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private double lastLat, lastLng;
    private boolean hasPosition = false;
    private Location lastGeocoded = null;
    private long lastMessageTime = 0;

    private final Runnable timeoutChecker = new Runnable() {
        @Override
        public void run() {
            if (lastMessageTime != 0
                    && SystemClock.elapsedRealtime() - lastMessageTime > DATA_TIMEOUT_MS) {
                tvStatus.setText("Aucune donnée reçue du collier (ESP32)");
            }
            uiHandler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        tvLatitude = findViewById(R.id.tvLatitude);
        tvLongitude = findViewById(R.id.tvLongitude);
        tvAltitude = findViewById(R.id.tvAltitude);
        tvLastUpdate = findViewById(R.id.tvLastUpdate);
        tvCity = findViewById(R.id.tvCity);
        tvStatus = findViewById(R.id.tvStatus);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnOpenMaps = findViewById(R.id.btnOpenMaps);

        btnRefresh.setOnClickListener(v -> connectMqtt());
        btnOpenMaps.setOnClickListener(v -> openInMaps());

        connectMqtt();
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiHandler.postDelayed(timeoutChecker, 3000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(timeoutChecker);
    }

    // ====================== MQTT ======================
    private void connectMqtt() {
        tvStatus.setText("Connexion au broker MQTT...");

        mqttExecutor.execute(() -> {
            try {
                // Ferme l'ancienne connexion si elle existe
                if (client != null) {
                    try {
                        client.disconnectForcibly(0);
                        client.close();
                    } catch (Exception ignored) { }
                }

                String clientId = "android-animora-" + System.currentTimeMillis();
                client = new MqttAsyncClient(BROKER_URI, clientId, new MemoryPersistence());

                client.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        // (re)abonnement a chaque connexion
                        subscribeGps();
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        runOnUiThread(() ->
                                tvStatus.setText("Connexion MQTT perdue, reconnexion..."));
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        runOnUiThread(() -> handleGpsMessage(payload));
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) { }
                });

                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setConnectionTimeout(10);
                options.setKeepAliveInterval(30);
                if (!MQTT_USER.isEmpty()) {
                    options.setUserName(MQTT_USER);
                    options.setPassword(MQTT_PASS.toCharArray());
                }

                client.connect(options, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        runOnUiThread(() ->
                                tvStatus.setText("Connecté au broker, en attente du GPS..."));
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        runOnUiThread(() -> {
                            tvStatus.setText("Échec connexion MQTT : "
                                    + exception.getMessage());
                            Toast.makeText(MapsActivity.this,
                                    "Vérifie l'IP du PC et que le téléphone est sur le même WiFi",
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("Erreur MQTT : " + e.getMessage()));
            }
        });
    }

    private void subscribeGps() {
        try {
            client.subscribe(TOPIC_GPS, 0);
        } catch (Exception e) {
            runOnUiThread(() -> tvStatus.setText("Erreur abonnement : " + e.getMessage()));
        }
    }

    // ====================== TRAITEMENT DES DONNEES ======================
    private void handleGpsMessage(String payload) {
        if (isFinishing() || isDestroyed()) return;

        try {
            JSONObject o = new JSONObject(payload);

            boolean fix = o.optBoolean("fix", false);

            lastMessageTime = SystemClock.elapsedRealtime();
            tvLastUpdate.setText("Dernière mise à jour: "
                    + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));

            if (!fix) {
                tvStatus.setText("En attente du fix GPS (placer le collier à l'extérieur)");
                return;
            }

            double lat = o.getDouble("lat");
            double lng = o.getDouble("lon");
            double alt = o.optDouble("alt", 0);

            lastLat = lat;
            lastLng = lng;
            hasPosition = true;

            tvLatitude.setText(String.format(Locale.US, "Latitude: %.6f", lat));
            tvLongitude.setText(String.format(Locale.US, "Longitude: %.6f", lng));
            tvAltitude.setText(String.format(Locale.US, "Altitude: %.1f m", alt));
            tvStatus.setText("Position reçue en direct");

            updateCityIfMoved(lat, lng);

        } catch (Exception e) {
            tvStatus.setText("Message GPS invalide");
        }
    }

    // ====================== GEOCODAGE ======================
    // On ne relance le geocodage que si l'animal a bouge de plus de 100 m
    private void updateCityIfMoved(double lat, double lng) {
        Location current = new Location("mqtt");
        current.setLatitude(lat);
        current.setLongitude(lng);

        if (lastGeocoded != null && lastGeocoded.distanceTo(current) < 100) return;
        lastGeocoded = current;

        getCityName(lat, lng);
    }

    private void getCityName(double lat, double lng) {
        if (!Geocoder.isPresent()) {
            tvCity.setText("Nom de la ville indisponible");
            return;
        }

        Geocoder geocoder = new Geocoder(this, Locale.getDefault());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lng, 1, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> addresses) {
                    runOnUiThread(() -> displayCity(addresses));
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> tvCity.setText("Ville indisponible"));
                }
            });
        } else {
            geocoderExecutor.execute(() -> {
                try {
                    List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                    runOnUiThread(() -> displayCity(addresses));
                } catch (IOException e) {
                    runOnUiThread(() -> tvCity.setText("Ville indisponible"));
                }
            });
        }
    }

    private void displayCity(List<Address> addresses) {
        if (addresses != null && !addresses.isEmpty()) {
            Address address = addresses.get(0);

            String city = address.getLocality();
            if (city == null) city = address.getSubAdminArea();
            if (city == null) city = address.getAdminArea();
            if (city == null) city = address.getCountryName();

            tvCity.setText(city != null ? city : "Ville indisponible");
        } else {
            tvCity.setText("Ville indisponible");
        }
    }

    // ====================== OUVRIR DANS GOOGLE MAPS ======================
    private void openInMaps() {
        if (!hasPosition) {
            Toast.makeText(this, "Aucune position reçue pour le moment",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Uri geoUri = Uri.parse(String.format(Locale.US,
                "geo:%f,%f?q=%f,%f(Pet)", lastLat, lastLng, lastLat, lastLng));
        Intent intent = new Intent(Intent.ACTION_VIEW, geoUri);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Uri web = Uri.parse(String.format(Locale.US,
                    "https://www.google.com/maps?q=%f,%f", lastLat, lastLng));
            startActivity(new Intent(Intent.ACTION_VIEW, web));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        geocoderExecutor.shutdown();
        mqttExecutor.execute(() -> {
            try {
                if (client != null) {
                    client.disconnectForcibly(0);
                    client.close();
                }
            } catch (Exception ignored) { }
        });
        mqttExecutor.shutdown();
    }
}