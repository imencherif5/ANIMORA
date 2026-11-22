
package com.example.animora;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("AnimoraSession", MODE_PRIVATE);

        boolean isLoggedIn = prefs.getBoolean("IS_LOGGED_IN", false);

        if (isLoggedIn) {
            Intent i = new Intent(MainActivity.this, dashboard.class);

            i.putExtra("USER_NAME", prefs.getString("USER_NAME", ""));
            i.putExtra("PET_NAME", prefs.getString("PET_NAME", ""));
            i.putExtra("PET_PHOTO", prefs.getString("PET_PHOTO", ""));

            startActivity(i);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        Button btn = findViewById(R.id.btn);

        btn.setOnClickListener(v -> {
            Intent i = new Intent(this, userLogin.class);
            startActivity(i);
        });
    }
}
