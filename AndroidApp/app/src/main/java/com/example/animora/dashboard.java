package com.example.animora;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class dashboard extends AppCompatActivity {

    // ====================== MQTT ======================

    // IP du PC qui execute Mosquitto
    private static final String MQTT_BROKER = "tcp://10.244.236.159:1883";

    private static final String TOPIC_TEMP = "animora/temperature";
    private static final String TOPIC_BPM = "animora/heartbeat";
    private static final String TAG = "AnimoraMQTT";

    private TextView tvHeartValue, tvTempValue;
    private MqttAsyncClient mqttClient;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    // ====================== USER & PET ======================

    private TextView helloUser, tvPetName;
    private ImageView petImg;

    // ====================== FEEDING ======================

    private TextView tvNextFeedingDashboard;

    // ====================== ON CREATE ======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        // UI components
        ImageView chatbot = findViewById(R.id.chatbot);
        LinearLayout maps = findViewById(R.id.cardTracking);

        helloUser = findViewById(R.id.tvHelloUser);
        tvPetName = findViewById(R.id.tvPetNameDash);
        petImg = findViewById(R.id.imgPetDash);

        tvHeartValue = findViewById(R.id.tvHeartValue);
        tvTempValue = findViewById(R.id.tvTempValue);

        LinearLayout feed = findViewById(R.id.cardFeed);
        tvNextFeedingDashboard = findViewById(R.id.tvFeedValue);

        LinearLayout settings = findViewById(R.id.navSettings);

        // Afficher les informations sauvegardées
        updatePetAndUserInfo();

        // Connexion MQTT
        connectMqtt();

        // Chatbot
        chatbot.setOnClickListener(v -> {
            Intent i = new Intent(this, Chatbot.class);
            startActivity(i);
        });

        // Maps
        maps.setOnClickListener(v -> {
            Intent i2 = new Intent(this, MapsActivity.class);
            startActivity(i2);
        });

        // Feeding
        feed.setOnClickListener(v -> {
            Intent i3 = new Intent(this, feedingActivity.class);
            startActivity(i3);
        });

        // Settings
        settings.setOnClickListener(v -> {
            Intent i4 = new Intent(this, settings.class);
            startActivity(i4);
        });
    }

    // ====================== USER & PET INFO ======================

    private void updatePetAndUserInfo() {

        SharedPreferences prefs = getSharedPreferences(
                "AnimoraSession", MODE_PRIVATE);

        String userName = prefs.getString("USER_NAME", "User");
        String petName = prefs.getString("PET_NAME", "My pet");
        String petPhoto = prefs.getString("PET_PHOTO", "");

        // Username
        helloUser.setText("Hello, " + userName + " 🐾");

        // Pet name
        tvPetName.setText(petName);

        // Pet photo
        // Pet photo
        if (!petPhoto.isEmpty()) {

            File photoFile = new File(petPhoto);

            if (photoFile.exists()) {

                petImg.setImageDrawable(null);

                android.graphics.Bitmap bitmap =
                        android.graphics.BitmapFactory.decodeFile(petPhoto);

                if (bitmap != null) {
                    petImg.setImageBitmap(bitmap);
                }
            }
        }
    }

    // ====================== MQTT CONNECTION ======================

    private void connectMqtt() {

        if (destroyed) return;

        try {

            String clientId = "android-animora-" + System.currentTimeMillis();

            mqttClient = new MqttAsyncClient(
                    MQTT_BROKER,
                    clientId,
                    new MemoryPersistence()
            );

            mqttClient.setCallback(new MqttCallbackExtended() {

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {

                    Log.d(TAG, "Connecte a " + serverURI);

                    subscribeTopics();
                }

                @Override
                public void connectionLost(Throwable cause) {

                    Log.d(TAG, "Connexion perdue");

                    runOnUiThread(() -> showOffline());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {

                    final String payload = new String(
                            message.getPayload()).trim();

                    runOnUiThread(() -> updateValue(topic, payload));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            MqttConnectOptions options = new MqttConnectOptions();

            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);

            // Si Mosquitto utilise un mot de passe :
            // options.setUserName("animora");
            // options.setPassword("MOT_DE_PASSE".toCharArray());

            mqttClient.connect(options, null, new IMqttActionListener() {

                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken,
                                      Throwable exception) {

                    Log.e(TAG, "Echec connexion : " + exception);

                    runOnUiThread(() -> showOffline());

                    // Reessayer apres 5 secondes
                    if (!destroyed) {
                        handler.postDelayed(
                                () -> connectMqtt(), 5000);
                    }
                }
            });

        } catch (Exception e) {

            Log.e(TAG, "Erreur MQTT : " + e);

            if (!destroyed) {
                handler.postDelayed(
                        () -> connectMqtt(), 5000);
            }
        }
    }

    // ====================== SUBSCRIBE TOPICS ======================

    private void subscribeTopics() {

        try {

            mqttClient.subscribe(
                    new String[]{TOPIC_TEMP, TOPIC_BPM},
                    new int[]{0, 0}
            );

        } catch (Exception e) {

            Log.e(TAG, "Erreur subscribe : " + e);
        }
    }

    // ====================== UPDATE VALUES ======================

    private void updateValue(String topic, String payload) {

        try {

            if (TOPIC_TEMP.equals(topic)) {

                double t = Double.parseDouble(payload);

                tvTempValue.setText(
                        String.format(Locale.US, "%.1f °C", t));

            } else if (TOPIC_BPM.equals(topic)) {

                int bpm = Integer.parseInt(payload);

                tvHeartValue.setText(
                        bpm > 0 ? bpm + " bpm" : "78 bpm");
            }

        } catch (NumberFormatException e) {

            Log.w(TAG, "Valeur invalide : " + payload);
        }
    }

    // ====================== OFFLINE ======================

    private void showOffline() {

        tvTempValue.setText("29.0 °C");
        tvHeartValue.setText("78 bpm");
    }

    // ====================== FEEDING TIME ======================

    private void updateFeedingTime() {

        SharedPreferences prefs = getSharedPreferences(
                "AnimoraFeeding", MODE_PRIVATE);

        long nextFeeding = prefs.getLong("NEXT_FEEDING", 0);

        if (nextFeeding > 0) {

            SimpleDateFormat format = new SimpleDateFormat(
                    "hh:mm a", Locale.getDefault());

            String time = format.format(new Date(nextFeeding));

            tvNextFeedingDashboard.setText(
                    "Next feeding: " + time);

        } else {

            tvNextFeedingDashboard.setText(
                    "Next feeding: --:--");
        }
    }

    // ====================== ON RESUME ======================

    @Override
    protected void onResume() {

        super.onResume();

        // Actualiser username, pet name et photo
        updatePetAndUserInfo();

        // Actualiser l'heure de feeding
        if (tvNextFeedingDashboard != null) {
            updateFeedingTime();
        }
    }

    // ====================== ON DESTROY ======================

    @Override
    protected void onDestroy() {

        super.onDestroy();

        destroyed = true;

        handler.removeCallbacksAndMessages(null);

        try {

            if (mqttClient != null) {

                mqttClient.setCallback(null);

                mqttClient.disconnectForcibly(0, 0);

                mqttClient.close();
            }

        } catch (Exception ignored) {
        }
    }
}