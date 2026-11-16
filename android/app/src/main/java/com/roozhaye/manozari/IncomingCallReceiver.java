package com.roozhaye.manozari;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

public class IncomingCallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state) || TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            CallAnnouncerService.stopActive(context);
            return;
        }

        if (!TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences("rmz_native_prefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("announce_calls_enabled", true)) {
            return;
        }

        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        String callerName = lookupContactName(context, incomingNumber);
        if (callerName == null || callerName.trim().isEmpty()) {
            callerName = (incomingNumber != null && !incomingNumber.trim().isEmpty())
                    ? incomingNumber.trim()
                    : "تماس ناشناس";
        }

        String spokenText = "تماس ورودی از طرف " + callerName;
        boolean isMale = "male".equals(prefs.getString("default_voice_gender", "female"));
        int repeat = 2;

        try {
            String jsonStr = prefs.getString("callers_json", "[]");
            JSONArray arr = new JSONArray(jsonStr);
            String normalizedTarget = callerName.trim().toLowerCase();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String name = obj.optString("name", "").trim();
                String latinAlias = obj.optString("latinAlias", "").trim();
                if ((!name.isEmpty() && name.equalsIgnoreCase(normalizedTarget))
                        || (!latinAlias.isEmpty() && latinAlias.equalsIgnoreCase(normalizedTarget))) {
                    String template = obj.optString("template", "تماس ورودی از طرف {name}");
                    String display = !obj.optString("persianPhonetic", "").isEmpty() && !name.matches(".*[A-Za-z].*")
                            ? name
                            : name;
                    spokenText = template.replace("{name}", display);
                    isMale = "male".equals(obj.optString("voiceGender", "female"));
                    repeat = obj.optInt("repeatCount", 2);
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        CallAnnouncerService.announce(context, "تماس ورودی: " + callerName, spokenText, isMale, repeat, true);
    }

    private String lookupContactName(Context context, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return null;
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
