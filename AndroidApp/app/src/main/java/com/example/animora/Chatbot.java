
package com.example.animora;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;

public class Chatbot extends AppCompatActivity {

    private EditText messageInput;
    private ImageButton sendButton;

    private LinearLayout chatContainer;
    private ScrollView chatScrollView;

    private JSONArray faqData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_chatbot);

        // Initialize views
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        chatContainer = findViewById(R.id.chatContainer);
        chatScrollView = findViewById(R.id.chatScrollView);
        LinearLayout settings = findViewById(R.id.navSettings);
        FrameLayout home = findViewById(R.id.navHome);

        settings.setOnClickListener(v -> {
            Intent i = new Intent(this, settings.class);
            startActivity(i);
        });

        home.setOnClickListener(v -> {
            Intent i2 = new Intent(this, dashboard.class);
            startActivity(i2);
        });

        // Load FAQ from JSON
        loadFAQ();

        // Welcome message
        addMessage(
                "Hello! Welcome to ANIMORA. 🐾 " +
                        "I'm your smart pet assistant. " +
                        "How can I help you today?",
                false
        );

        // Send button
        sendButton.setOnClickListener(v -> sendMessage());

        // Send using keyboard
        messageInput.setOnEditorActionListener((v, actionId, event) -> {

            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }

            return false;
        });

        // Quick questions
        findViewById(R.id.questionGPS).setOnClickListener(v ->
                sendQuickQuestion("How does GPS tracking work?"));

        findViewById(R.id.questionActivity).setOnClickListener(v ->
                sendQuickQuestion("What does MPU6050 do?"));

        findViewById(R.id.questionTemperature).setOnClickListener(v ->
                sendQuickQuestion("How does temperature monitoring work?"));

        findViewById(R.id.questionHeart).setOnClickListener(v ->
                sendQuickQuestion("What is heart rate?"));
    }

    // Load JSON file from assets
    private void loadFAQ() {

        try {

            InputStream inputStream =
                    getAssets().open("chatbot_faq.json");

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, "UTF-8")
            );

            StringBuilder jsonBuilder = new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }

            reader.close();

            faqData = new JSONArray(jsonBuilder.toString());

        } catch (Exception e) {

            faqData = new JSONArray();

            Toast.makeText(
                    this,
                    "Error loading chatbot FAQ",
                    Toast.LENGTH_LONG
            ).show();

            e.printStackTrace();
        }
    }

    // Send a message from a quick question
    private void sendQuickQuestion(String question) {

        messageInput.setText(question);

        sendMessage();
    }

    // Send user message
    private void sendMessage() {

        String userMessage = messageInput.getText()
                .toString()
                .trim();

        if (userMessage.isEmpty()) {
            return;
        }

        // Display user question
        addMessage(userMessage, true);

        // Clear input
        messageInput.setText("");

        // Get chatbot response
        String response = getBotResponse(userMessage);

        // Display chatbot response
        addMessage(response, false);
    }

    // Search for the answer in JSON
    private String getBotResponse(String question) {

        if (faqData == null || faqData.length() == 0) {

            return "Sorry, the chatbot FAQ is not available.";
        }

        String normalizedQuestion = normalize(question);

        try {

            for (int i = 0; i < faqData.length(); i++) {

                JSONObject item = faqData.getJSONObject(i);

                JSONArray questions =
                        item.getJSONArray("questions");

                String answer =
                        item.getString("answer");

                for (int j = 0; j < questions.length(); j++) {

                    String storedQuestion = normalize(
                            questions.getString(j)
                    );

                    if (normalizedQuestion.equals(storedQuestion)) {

                        return answer;
                    }
                }
            }

        } catch (Exception e) {

            e.printStackTrace();
        }

        return "I'm sorry, I don't have an answer to this question yet. " +
                "Try asking me about GPS, activity, temperature, " +
                "heart rate or ANIMORA features.";
    }

    // Normalize text before comparison
    private String normalize(String text) {

        return text.toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll("\\s+", " ");
    }

    // Display messages in the chat
    private void addMessage(String message, boolean isUser) {

        TextView textView = new TextView(this);

        textView.setText(message);
        textView.setTextSize(14);
        textView.setLineSpacing(4, 1.0f);

        textView.setMaxWidth(
                (int) (getResources()
                        .getDisplayMetrics().widthPixels * 0.78f)
        );

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.setMargins(8, 8, 8, 8);

        if (isUser) {

            params.gravity = Gravity.END;

            textView.setBackgroundResource(
                    R.drawable.chat_user_bg
            );

            textView.setTextColor(
                    android.graphics.Color.WHITE
            );

        } else {

            params.gravity = Gravity.START;

            textView.setBackgroundResource(
                    R.drawable.chat_bot_bg
            );

            textView.setTextColor(
                    android.graphics.Color.rgb(53, 75, 80)
            );
        }

        textView.setLayoutParams(params);

        chatContainer.addView(textView);

        // Automatic scrolling to the latest message
        chatScrollView.post(() ->
                chatScrollView.fullScroll(
                        ScrollView.FOCUS_DOWN
                )
        );
    }
}
