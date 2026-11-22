
package com.example.animora;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import android.content.SharedPreferences;

public class feedingActivity extends AppCompatActivity {

    private TimePicker timePickerLast;
    private Spinner spinnerInterval;
    private TextView tvNextFeeding;
    private Button btnSaveFeeding;

    private Calendar nextFeeding;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feeding);

        timePickerLast = findViewById(R.id.timePickerLast);
        spinnerInterval = findViewById(R.id.spinnerInterval);
        tvNextFeeding = findViewById(R.id.tvNextFeeding);
        btnSaveFeeding = findViewById(R.id.btnSaveFeeding);

        prefs = getSharedPreferences("AnimoraFeeding", MODE_PRIVATE);

        // Intervals
        String[] intervals = {"2 hours", "3 hours", "4 hours",
                "5 hours", "6 hours", "8 hours"};

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                intervals
        );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        spinnerInterval.setAdapter(adapter);

        // Restore saved settings
        loadSavedSchedule();

        // Save button
        btnSaveFeeding.setOnClickListener(v -> {
            saveSchedule();
            finish();
        });
    }

    private int getSelectedInterval() {
        String selected = spinnerInterval.getSelectedItem().toString();
        return Integer.parseInt(selected.split(" ")[0]);
    }

    private Calendar calculateNextFeeding() {

        int hour;
        int minute;

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            hour = timePickerLast.getHour();
            minute = timePickerLast.getMinute();
        } else {
            hour = timePickerLast.getCurrentHour();
            minute = timePickerLast.getCurrentMinute();
        }

        int interval = getSelectedInterval();

        // Last feeding time
        Calendar lastFeeding = Calendar.getInstance();
        lastFeeding.set(Calendar.HOUR_OF_DAY, hour);
        lastFeeding.set(Calendar.MINUTE, minute);
        lastFeeding.set(Calendar.SECOND, 0);
        lastFeeding.set(Calendar.MILLISECOND, 0);

        Calendar now = Calendar.getInstance();

        // If selected time is in the future, assume last feeding was yesterday
        if (lastFeeding.after(now)) {
            lastFeeding.add(Calendar.DAY_OF_MONTH, -1);
        }

        // Calculate next feeding
        Calendar next = (Calendar) lastFeeding.clone();
        next.add(Calendar.HOUR_OF_DAY, interval);

        // If next feeding already passed, move forward by intervals
        while (!next.after(now)) {
            next.add(Calendar.HOUR_OF_DAY, interval);
        }

        return next;
    }

    private void saveSchedule() {

        nextFeeding = calculateNextFeeding();

        int hour;
        int minute;

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            hour = timePickerLast.getHour();
            minute = timePickerLast.getMinute();
        } else {
            hour = timePickerLast.getCurrentHour();
            minute = timePickerLast.getCurrentMinute();
        }

        int interval = getSelectedInterval();

        SimpleDateFormat format = new SimpleDateFormat(
                "hh:mm a", Locale.getDefault()
        );

        String nextTime = format.format(nextFeeding.getTime());

        tvNextFeeding.setText("Next feeding: " + nextTime);

        // Save data
        prefs.edit()
                .putInt("LAST_HOUR", hour)
                .putInt("LAST_MINUTE", minute)
                .putInt("INTERVAL", interval)
                .putLong("NEXT_FEEDING", nextFeeding.getTimeInMillis())
                .apply();

        Toast.makeText(this, "Feeding schedule saved!", Toast.LENGTH_SHORT).show();
    }

    private void loadSavedSchedule() {

        if (!prefs.contains("INTERVAL")) {
            return;
        }

        int savedHour = prefs.getInt("LAST_HOUR", 8);
        int savedMinute = prefs.getInt("LAST_MINUTE", 0);
        int savedInterval = prefs.getInt("INTERVAL", 5);

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            timePickerLast.setHour(savedHour);
            timePickerLast.setMinute(savedMinute);
        } else {
            timePickerLast.setCurrentHour(savedHour);
            timePickerLast.setCurrentMinute(savedMinute);
        }

        // Select saved interval
        String[] intervals = {"2 hours", "3 hours", "4 hours",
                "5 hours", "6 hours", "8 hours"};

        for (int i = 0; i < intervals.length; i++) {
            if (intervals[i].startsWith(savedInterval + " ")) {
                spinnerInterval.setSelection(i);
                break;
            }
        }

        long savedNext = prefs.getLong("NEXT_FEEDING", 0);

        if (savedNext > 0) {
            Calendar savedCalendar = Calendar.getInstance();
            savedCalendar.setTimeInMillis(savedNext);

            SimpleDateFormat format = new SimpleDateFormat(
                    "hh:mm a", Locale.getDefault()
            );

            tvNextFeeding.setText(
                    "Next feeding: " + format.format(savedCalendar.getTime())
            );
        }
    }
}
