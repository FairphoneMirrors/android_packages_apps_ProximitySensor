package com.fairphone.psensor;

import android.app.Application;

import com.fairphone.psensor.notifications.NotificationUtils;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationUtils.createNotificationChannel(this);
    }
}
