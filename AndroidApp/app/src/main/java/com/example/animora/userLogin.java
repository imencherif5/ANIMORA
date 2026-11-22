package com.example.animora;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

public class userLogin extends AppCompatActivity {

    EditText etUserName;
    Button btnNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_login);

        etUserName = findViewById(R.id.etUserName);
        btnNext = findViewById(R.id.btnNext);

        btnNext.setOnClickListener(v -> {

            String userName = etUserName.getText().toString().trim();

            if (userName.isEmpty()) {
                etUserName.setError("Please enter your username");
                etUserName.requestFocus();
            } else {
                Intent intent = new Intent(userLogin.this, petLogin.class);
                intent.putExtra("USER_NAME", userName);
                startActivity(intent);
                finish();
            }
        });
    }
}
