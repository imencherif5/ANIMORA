package com.example.animora;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class settings extends AppCompatActivity {

    TextView btnChangeUserName, btnChangePetName,
            btnChangePetPhoto, btnAboutAnimora, btnLogout;

    SharedPreferences prefs;

    ActivityResultLauncher<String[]> photoPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Initialisation
        btnChangeUserName = findViewById(R.id.btnChangeUserName);
        btnChangePetName = findViewById(R.id.btnChangePetName);
        btnChangePetPhoto = findViewById(R.id.btnChangePetPhoto);
        btnAboutAnimora = findViewById(R.id.btnAboutAnimora);
        btnLogout = findViewById(R.id.btnLogout);
        FrameLayout home = findViewById(R.id.navHome);
        LinearLayout chat = findViewById(R.id.navChat);

        home.setOnClickListener(v -> {
            Intent i = new Intent(this,dashboard.class);
            startActivity(i);
        });

        chat.setOnClickListener(v -> {
            Intent i2 = new Intent(this,Chatbot.class);
            startActivity(i2);
        });

        // Utilisation du SharedPreferences existant
        prefs = getSharedPreferences("AnimoraSession", MODE_PRIVATE);

        // Galerie
        photoPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        String photoPath = savePhoto(uri);

                        if (photoPath != null) {
                            prefs.edit()
                                    .putString("PET_PHOTO", photoPath)
                                    .apply();

                            Toast.makeText(this,
                                    "Pet photo updated successfully!",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this,
                                    "Could not save the photo!",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        // Click listeners
        btnChangeUserName.setOnClickListener(v -> changeUserName());

        btnChangePetName.setOnClickListener(v -> changePetName());

        btnChangePetPhoto.setOnClickListener(v -> changePetPhoto());

        btnAboutAnimora.setOnClickListener(v -> showAbout());

        btnLogout.setOnClickListener(v -> logout());
    }

    // ================= USER NAME =================

    private void changeUserName() {

        EditText input = new EditText(this);
        input.setHint("Enter your new username");

        input.setText(prefs.getString("USER_NAME", ""));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Change User Name")
                .setView(input)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {

                        String newName = input.getText()
                                .toString().trim();

                        if (newName.isEmpty()) {
                            input.setError("Username cannot be empty");
                            return;
                        }

                        prefs.edit()
                                .putString("USER_NAME", newName)
                                .apply();

                        Toast.makeText(this,
                                "Username updated!",
                                Toast.LENGTH_SHORT).show();

                        dialog.dismiss();
                    });
        });

        dialog.show();
    }

    // ================= PET NAME =================

    private void changePetName() {

        EditText input = new EditText(this);
        input.setHint("Enter your pet's new name");

        input.setText(prefs.getString("PET_NAME", ""));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Change Pet Name")
                .setView(input)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {

                        String newName = input.getText()
                                .toString().trim();

                        if (newName.isEmpty()) {
                            input.setError("Pet name cannot be empty");
                            return;
                        }

                        prefs.edit()
                                .putString("PET_NAME", newName)
                                .apply();

                        Toast.makeText(this,
                                "Pet name updated!",
                                Toast.LENGTH_SHORT).show();

                        dialog.dismiss();
                    });
        });

        dialog.show();
    }

    // ================= PET PHOTO =================

    private void changePetPhoto() {

        photoPicker.launch(new String[]{"image/*"});
    }

    // Copier la photo vers le stockage interne
    private String savePhoto(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = openFileOutput("pet_photo.jpg", MODE_PRIVATE)) {

            if (in == null) return null;

            byte[] buffer = new byte[4096];
            int length;

            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }

            return new File(getFilesDir(), "pet_photo.jpg").getAbsolutePath();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ================= ABOUT =================

    private void showAbout() {

        new AlertDialog.Builder(this)
                .setTitle("About Animora")
                .setMessage(
                        "ANIMORA\n\n" +
                                "A Smarter Way To Protect Your Pet.\n\n" +
                                "Animora is a mobile application designed " +
                                "to help pet owners monitor their pets' " +
                                "health, activity and well-being using " +
                                "connected sensors and smart features.\n\n" +
                                "Version 1.0.0"
                )
                .setPositiveButton("OK", null)
                .show();
    }

    // ================= LOGOUT =================

    private void logout() {

        new AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Yes", (dialog, which) -> {

                    // Supprimer la session enregistrée
                    prefs.edit().clear().apply();

                    Intent intent = new Intent(
                            settings.this, MainActivity.class);

                    intent.setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK |
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                    );

                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}