package com.example.animora;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class petLogin extends AppCompatActivity {

    EditText etPetName;
    ImageView imgPet;
    Button btnUploadPhoto, btnContinue;

    Uri imageUri = null;

    ActivityResultLauncher<String> imagePicker =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            imageUri = uri;
                            imgPet.setImageURI(imageUri);
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pet_login);

        etPetName = findViewById(R.id.etPetName);
        imgPet = findViewById(R.id.imgPet);
        btnUploadPhoto = findViewById(R.id.btnUploadPhoto);
        btnContinue = findViewById(R.id.btnContinue);

        String userName = getIntent().getStringExtra("USER_NAME");

        // Upload pet photo
        btnUploadPhoto.setOnClickListener(v -> imagePicker.launch("image/*"));

        // Continue button
        btnContinue.setOnClickListener(v -> {

            String petName = etPetName.getText().toString().trim();

            if (petName.isEmpty()) {
                etPetName.setError("Please enter your pet's name");
                etPetName.requestFocus();
            } else if (imageUri == null) {
                Toast.makeText(
                        petLogin.this,
                        "Please upload a photo of your pet",
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                // copie de la photo dans le stockage interne de l'application
                String photoPath = savePhoto(imageUri);

                if (photoPath == null) {
                    Toast.makeText(
                            petLogin.this,
                            "Could not save the photo, please try again",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                Toast.makeText(
                        petLogin.this,
                        "Pet information saved successfully!",
                        Toast.LENGTH_SHORT
                ).show();

                // Save login session
                SharedPreferences prefs = getSharedPreferences(
                        "AnimoraSession",
                        MODE_PRIVATE
                );

                prefs.edit()
                        .putBoolean("IS_LOGGED_IN", true)
                        .putString("USER_NAME", userName)
                        .putString("PET_NAME", petName)
                        .putString("PET_PHOTO", photoPath)
                        .apply();

                Intent i = new Intent(petLogin.this, dashboard.class);
                i.putExtra("USER_NAME", userName);
                i.putExtra("PET_NAME", petName);
                i.putExtra("PET_PHOTO", photoPath);
                startActivity(i);
                finish(); // le bouton retour ne revient plus a cette page
            }
        });
    }

    // Copie l'image choisie vers le stockage interne et retourne son chemin
    private String savePhoto(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = openFileOutput("pet_photo.jpg", MODE_PRIVATE)) {

            if (in == null) {
                return null;
            }

            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            return new File(getFilesDir(), "pet_photo.jpg").getAbsolutePath();

        } catch (Exception e) {
            return null;
        }
    }
}
