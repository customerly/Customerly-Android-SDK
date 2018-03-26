package io.customerly;

/*
 * Copyright (C) 2017 Customerly
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.CheckResult;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.Spanned;
import android.util.Base64;
import android.util.Log;
import android.util.Patterns;
import android.view.WindowManager;

import com.github.zafarkhaja.semver.Version;

import org.intellij.lang.annotations.Subst;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * The singleton representing the Customerly SDK
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class Customerly {

    protected static final String NOTIFICATION_CHANNEL_ID_DOWNLOAD = "io.customerly.customerly_sdk.notification_channel_download";
    private static final String PREFS_PING_RESPONSE__WIDGET_COLOR = "PREFS_PING_RESPONSE__WIDGET_COLOR";
    private static final String PREFS_PING_RESPONSE__BACKGROUND_THEME_URL = "PREFS_PING_RESPONSE__BACKGROUND_THEME_URL";
    private static final String PREFS_PING_RESPONSE__POWERED_BY = "PREFS_PING_RESPONSE__POWERED_BY";
    private static final String PREFS_PING_RESPONSE__WELCOME_USERS = "PREFS_PING_RESPONSE__WELCOME_USERS";
    private static final String PREFS_PING_RESPONSE__WELCOME_VISITORS = "PREFS_PING_RESPONSE__WELCOME_VISITORS";
    private static final String PREF_CURRENT_EMAIL = "regusrml", PREF_CURRENT_ID = "regusrid", PREF_CURRENT_COMPANY_INFO = "cmpnynfo";
    private static final long SOCKET_PING_INTERVAL = 60000, SURVEY_DISPLAY_DELAY = 5000L;
    private static final String SOCKET_EVENT__TYPING = "typing";
    private static final String SOCKET_EVENT__SEEN = "seen";
    private static final String SOCKET_EVENT__MESSAGE = "message";
    @ColorInt private static final int DEF_WIDGET_COLOR_MALIBU_INT = 0xff65a9e7;//Blue Malibu

    @NonNull final IU_RemoteImageHandler _RemoteImageHandler = new IU_RemoteImageHandler();
    @NonNull private final Handler __Handler = new Handler();

    private boolean initialized = false;
    private @Nullable SharedPreferences _SharedPreferences;
    @Nullable String _AppID;//useless: _AppCacheDir;
    @ColorInt private int
            __WidgetColor__Fallback = DEF_WIDGET_COLOR_MALIBU_INT,
            __WidgetColor__HardCoded = Color.TRANSPARENT;

    @Nullable IE_JwtToken _JwtToken;

    private boolean __VerboseLogging = false;

    @Nullable private Socket _Socket;

    @NonNull private String __PING__LAST_min_version = "0.0.0";
    private long __PING__next_ping_allowed = 0L;
    @ColorInt int __PING__LAST_widget_color;
    @Nullable String __PING__LAST_widget_background_url;
    boolean __PING__LAST_powered_by;
    @Nullable private String __PING__LAST_welcome_message_users, __PING__LAST_welcome_message_visitors;
    @Nullable IE_Admin[] __PING__LAST_active_admins;
    private boolean _SupportEnabled = true, _SurveyEnabled = true;

    private boolean __isAppInsolvent = false;

    @NonNull JSONObject __PING__DeviceJSON = new JSONObject();

    @Nullable private ArrayList<Class<? extends Activity>> _DisabledActivities = null;
    @Nullable private Runnable _PendingRunnableForNotDisabledActivity = null;

    @Nullable private WeakReference<Activity> _CurrentActivity;
    @NonNull private final Application.ActivityLifecycleCallbacks __ActivityLifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
        private boolean paused = false, foreground = false;
        @NonNull private Runnable checkBackground = () -> {
            if (this.foreground && this.paused) {
                this.foreground = false;
                __SOCKET__disconnect();
            }
        };
        @Override public void onActivityResumed(Activity activity) {
            _CurrentActivity = new WeakReference<>(activity);
            this.paused = false;
            boolean wasBackground = !this.foreground;
            this.foreground = true;
            __Handler.removeCallbacks(this.checkBackground);
            if (wasBackground && _isConfigured()) {
                __SOCKET__connect(null);
                __PING__Start(null, null);
            }
            Runnable pendingRunnableForNotDisabledActivity = _PendingRunnableForNotDisabledActivity;
            ArrayList<Class<? extends Activity>> disabledActivities = _DisabledActivities;
            if(pendingRunnableForNotDisabledActivity != null && (disabledActivities == null || ! disabledActivities.contains(activity.getClass()))) {
                pendingRunnableForNotDisabledActivity.run();
            }
        }
        @Override public void onActivityPaused(Activity activity) {
            this.paused = true;
            __Handler.removeCallbacks(this.checkBackground);
            __Handler.postDelayed(this.checkBackground, 500);
        }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
        @Override public void onActivityDestroyed(Activity activity) {
            PW_AlertMessage.onActivityDestroyed(activity);//Need to dismiss the alert or leak window exception comes out
        }
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
        @Override public void onActivityStarted(Activity activity) { }
    };

    private class PingResponseConverter implements IApi_Request.ResponseConverter<Void> {
        private final boolean _HandleAlertMessage, _HandleSurvey;
        private PingResponseConverter(boolean handleSurvey, boolean handleAlertMessage) {
            super();
            this._HandleSurvey = handleSurvey;
            this._HandleAlertMessage = handleAlertMessage;
        }
        @Nullable
        @Override
        public final Void convert(@NonNull JSONObject root) throws JSONException {
            __PING__LAST_min_version = root.optString("min-version-android", "0.0.0");
            __PING__next_ping_allowed = root.optLong("next-ping-allowed", 0);
            __SOCKET__connect(root.optJSONObject("websocket"));
            JSONObject app_config = root.optJSONObject("app_config");
            if(app_config != null) {
                if(__WidgetColor__HardCoded == Color.TRANSPARENT) {
                    String pingWidgetColor = IU_Utils.jsonOptStringWithNullCheck(app_config, "widget_color");
                    if (pingWidgetColor != null && pingWidgetColor.length() != 0) {
                        if (pingWidgetColor.charAt(0) != '#') {
                            pingWidgetColor = '#' + pingWidgetColor;
                        }
                        try {
                            __PING__LAST_widget_color = Color.parseColor(pingWidgetColor);
                        } catch (IllegalArgumentException notCorrectColor) {
                            IEr_CustomerlyErrorHandler.sendError(IEr_CustomerlyErrorHandler.ERROR_CODE__HTTP_RESPONSE_ERROR, String.format("PingResponse:data.apps.app_config.widget_color is an invalid argb color: '%s'", pingWidgetColor), notCorrectColor);
                            __PING__LAST_widget_color = __WidgetColor__Fallback;
                        }
                    }
                }
                __PING__LAST_widget_background_url = IU_Utils.jsonOptStringWithNullCheck(app_config, "widget_background_url");
                __PING__LAST_powered_by = 1 == app_config.optLong("powered_by", 0);
                __PING__LAST_welcome_message_users = IU_Utils.jsonOptStringWithNullCheck(app_config, "welcome_message_users");
                __PING__LAST_welcome_message_visitors = IU_Utils.jsonOptStringWithNullCheck(app_config, "welcome_message_visitors");
            } else {
                __PING__LAST_widget_color = __WidgetColor__Fallback;
                __PING__LAST_widget_background_url = null;
                __PING__LAST_powered_by = false;
                __PING__LAST_welcome_message_users = null;
                __PING__LAST_welcome_message_visitors = null;
            }
            __PING__LAST_active_admins = IE_Admin.from(root.optJSONArray("active_admins"));

            final SharedPreferences prefs = _SharedPreferences;
            if(prefs != null) {
                prefs.edit()
                        .putInt(PREFS_PING_RESPONSE__WIDGET_COLOR, __PING__LAST_widget_color)
                        .putString(PREFS_PING_RESPONSE__BACKGROUND_THEME_URL, __PING__LAST_widget_background_url)
                        .putBoolean(PREFS_PING_RESPONSE__POWERED_BY, __PING__LAST_powered_by)
                        .putString(PREFS_PING_RESPONSE__WELCOME_USERS, __PING__LAST_welcome_message_users)
                        .putString(PREFS_PING_RESPONSE__WELCOME_VISITORS, __PING__LAST_welcome_message_visitors)
                        .apply();
            }

            _PendingRunnableForNotDisabledActivity = null;
            if(this._HandleSurvey && _SurveyEnabled) {
                _log("Attempting to display a Survey");
                IE_Survey[] surveys = IE_Survey.from(root.optJSONArray("last_surveys"));
                if(surveys != null) {
                    for (final IE_Survey survey : surveys) {
                        if (survey != null && !survey.isRejectedOrConcluded) {
                            __Handler.postDelayed(() -> {
                                Activity activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                ArrayList<Class<? extends Activity>> disabledActivities = _DisabledActivities;
                                if (activity != null && (disabledActivities == null || ! disabledActivities.contains(activity.getClass()))) {
                                    try {
                                        try {
                                            IDlgF_Survey.show(activity, survey);
                                        } catch (WindowManager.BadTokenException changedActivityWhileRunning) {
                                            activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                            if (activity != null) {
                                                try {
                                                    IDlgF_Survey.show(activity, survey);
                                                    _log("Survey successfully displayed");
                                                } catch (WindowManager.BadTokenException ignored) {
                                                    //Second failure.
                                                }
                                            }
                                        }
                                    } catch (Exception generic) {
                                        _log("A generic error occurred in Customerly.openSurvey");
                                        IEr_CustomerlyErrorHandler.sendError(IEr_CustomerlyErrorHandler.ERROR_CODE__GENERIC, "Generic error in Customerly.openSurvey", generic);
                                    }
                                } else {
                                    _PendingRunnableForNotDisabledActivity = () -> __Handler.postDelayed(() -> {
                                        Activity p_activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                        ArrayList<Class<? extends Activity>> p_disabledActivities = _DisabledActivities;
                                        if (p_activity != null && (p_disabledActivities == null || ! p_disabledActivities.contains(p_activity.getClass()))) {
                                            _PendingRunnableForNotDisabledActivity = null;
                                            try {
                                                try {
                                                    IDlgF_Survey.show(p_activity, survey);
                                                } catch (WindowManager.BadTokenException changedActivityWhileRunning) {
                                                    p_activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                                    if (p_activity != null) {
                                                        try {
                                                            IDlgF_Survey.show(p_activity, survey);
                                                            _log("Survey successfully displayed");
                                                        } catch (WindowManager.BadTokenException ignored) {
                                                            //Second failure.
                                                        }
                                                    }
                                                }
                                            } catch (Exception generic) {
                                                _log("A generic error occurred in Customerly.openSurvey");
                                                IEr_CustomerlyErrorHandler.sendError(IEr_CustomerlyErrorHandler.ERROR_CODE__GENERIC, "Generic error in Customerly.openSurvey", generic);
                                            }
                                        }
                                    }, SURVEY_DISPLAY_DELAY);
                                }
                            }, SURVEY_DISPLAY_DELAY);
                            return null;
                        }
                    }
                }
                _log("No Survey to display");
            }

            if(this._HandleAlertMessage && _SupportEnabled) {
                _log("Attempting to display an unread message");
                JSONArray last_messages_array = root.optJSONArray("last_messages");
                if (last_messages_array != null && last_messages_array.length() != 0) {
                    for (int i = 0; i < last_messages_array.length(); i++) {
                        try {
                            final JSONObject message = last_messages_array.getJSONObject(i);
                            if (message != null) {
                                __Handler.post(() -> {
                                    Activity activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                    ArrayList<Class<? extends Activity>> disabledActivities = _DisabledActivities;
                                    if (activity != null && (disabledActivities == null || ! disabledActivities.contains(activity.getClass()))) {
                                        if (activity instanceof SDKActivity) {
                                            ArrayList<IE_Message> list = new ArrayList<>(1);
                                            list.add(new IE_Message(message));
                                            ((SDKActivity) activity).onNewSocketMessages(list);
                                        } else {
                                            try {
                                                PW_AlertMessage.show(activity, new IE_Message(message));
                                                _log("Message alert displayed successfully");
                                            } catch (WindowManager.BadTokenException changedActivityWhileExecuting) {
                                                activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                                if (activity != null) {
                                                    if (activity instanceof SDKActivity) {
                                                        ArrayList<IE_Message> list = new ArrayList<>(1);
                                                        list.add(new IE_Message(message));
                                                        ((SDKActivity) activity).onNewSocketMessages(list);
                                                    } else {
                                                        try {
                                                            PW_AlertMessage.show(activity, new IE_Message(message));
                                                            _log("Message alert displayed successfully");
                                                        } catch (WindowManager.BadTokenException ignored) {
                                                            _log("An error occours while attaching the alertmessage to the window. Activity: " + activity.toString());
                                                            //Second try failure.
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        _PendingRunnableForNotDisabledActivity = () -> __Handler.postDelayed(() -> {
                                            Activity p_activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                            ArrayList<Class<? extends Activity>> p_disabledActivities = _DisabledActivities;
                                            if (p_activity != null && (p_disabledActivities == null || ! p_disabledActivities.contains(p_activity.getClass()))) {
                                                _PendingRunnableForNotDisabledActivity = null;
                                                if (p_activity instanceof SDKActivity) {
                                                    ArrayList<IE_Message> list = new ArrayList<>(1);
                                                    list.add(new IE_Message(message));
                                                    ((SDKActivity) p_activity).onNewSocketMessages(list);
                                                } else {
                                                    try {
                                                        PW_AlertMessage.show(p_activity, new IE_Message(message));
                                                        _log("Message alert displayed successfully");
                                                    } catch (WindowManager.BadTokenException changedActivityWhileExecuting) {
                                                        p_activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                                        if (p_activity != null) {
                                                            if (p_activity instanceof SDKActivity) {
                                                                ArrayList<IE_Message> list = new ArrayList<>(1);
                                                                list.add(new IE_Message(message));
                                                                ((SDKActivity) p_activity).onNewSocketMessages(list);
                                                            } else {
                                                                try {
                                                                    PW_AlertMessage.show(p_activity, new IE_Message(message));
                                                                    _log("Message alert displayed successfully");
                                                                } catch (WindowManager.BadTokenException ignored) {
                                                                    _log("An error occours while attaching the alertmessage to the window. Activity: " + p_activity.toString());
                                                                    //Second try failure.
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }, 500);//Delay needed or popup is not showed
                                    }
                                });
                                return null;
                            }
                        } catch (JSONException ignored) {
                        }
                    }
                }
                _log("no messages to display");
            }
            return null;
        }
    }

    @NonNull private final PingResponseConverter __PING__response_converter__SurveyMessage = new PingResponseConverter(true, true),
            __PING__response_converter__Message = new PingResponseConverter(false, true),
            __PING__response_converter__NaN = new PingResponseConverter(false, false);

    @NonNull private static final Customerly _Instance = new Customerly();

    private Customerly() {
        super();
        try {
            this.__PING__DeviceJSON.put("os", "Android")
                    .put("device", String.format("%s %s (%s)", Build.MANUFACTURER, Build.MODEL, Build.DEVICE))
                    .put("os_version", Build.VERSION.SDK_INT)
                    .put("sdk_version", BuildConfig.VERSION_NAME)
                    .put("api_version", BuildConfig.CUSTOMERLY_API_VERSION)
                    .put("socket_version", BuildConfig.CUSTOMERLY_SOCKET_VERSION);
        } catch (JSONException ignored) { }
    }

    boolean _isConfigured() {
        return this._isConfigured(true);
    }

    boolean _isConfigured(boolean reportNotConfiguredErrorDisabled) {
        if(this._AppID == null) {
            this._log("You need to configure the SDK ");
            if(!reportNotConfiguredErrorDisabled) {
                IEr_CustomerlyErrorHandler.sendNotConfiguredError();
            }
            return false;
        } else {
            return this.isSDKavailable();
        }
    }

    void _log(@NonNull String pLogMessage) {
        if(this.__VerboseLogging) {
            Log.v(BuildConfig.CUSTOMERLY_SDK_NAME, pLogMessage);
        }
    }

    private void _DEV_log(@NonNull String message) {
        if(BuildConfig.CUSTOMERLY_DEV_MODE) {
            Log.e(BuildConfig.CUSTOMERLY_SDK_NAME, message);
        }
    }

    @Nullable Spanned _WELCOME__getMessage() {
        IE_JwtToken token = this._JwtToken;
        return this._isConfigured()
                ? IU_Utils.fromHtml(token != null && token.isUser() ? this.__PING__LAST_welcome_message_users : this.__PING__LAST_welcome_message_visitors, null, null)
                : null;
    }

    interface __SOCKET__ITyping_listener {   void onTypingEvent(long pConversationID, long account_id, boolean pTyping);   }
    @Nullable __SOCKET__ITyping_listener __SOCKET__Typing_listener = null;
    @Nullable private String __SOCKET__Token = null, __SOCKET__Endpoint = null, __SOCKET__Port = null;
    @Nullable private String __SOCKET__CurrentConfiguration = null;
    private boolean __SOCKET__shouldBeConnected = false;
    interface SDKActivity {
        void onNewSocketMessages(@NonNull ArrayList<IE_Message> messages);
        void onLogoutUser();
    }
    private void __SOCKET__connect(@Nullable JSONObject webSocket) {
        if (webSocket != null && this.isSDKavailable()) {
        /*  "webSocket": {
              "endpoint": "https://ws2.customerly.io",
              "port": "8080"  }  */
            this.__SOCKET__Token = IU_Utils.jsonOptStringWithNullCheck(webSocket, "token");
            this.__SOCKET__Endpoint = IU_Utils.jsonOptStringWithNullCheck(webSocket, "endpoint");
            this.__SOCKET__Port = IU_Utils.jsonOptStringWithNullCheck(webSocket, "port");
        }

        if(this._SupportEnabled) {
            this.__SOCKET__shouldBeConnected = true;//Metto a true perchè potremmo non entrare nei successivi if
            if (this._AppID != null && this.__SOCKET__Token != null && this.__SOCKET__Endpoint != null && this.__SOCKET__Port != null) {
                IE_JwtToken token = this._JwtToken;
                if (token != null && token._UserID != null) {
                    Socket socket = this._Socket;
                    if (socket == null || ! socket.connected() || this.__SOCKET__CurrentConfiguration == null || !this.__SOCKET__CurrentConfiguration.equals(String.format(Locale.UK, "%s-%s-%s-%d", this.__SOCKET__Token, this.__SOCKET__Endpoint, this.__SOCKET__Port, token._UserID))) {

                        this.__SOCKET__disconnect();
                        this.__SOCKET__shouldBeConnected = true;//Rimetto a true perchè la disconnect l'ha messo a false
                        String currentConfiguration = String.format(Locale.UK, "%s-%s-%s-%d", this.__SOCKET__Token, this.__SOCKET__Endpoint, this.__SOCKET__Port, token._UserID);

                        String query;
                        try {
                            query = "token=" +
                                    URLEncoder.encode(Base64.encodeToString(
                                new JSONObject(new String(Base64.decode(this.__SOCKET__Token, Base64.DEFAULT), "UTF-8"))
                                        .put("is_mobile", true).put("socket_version", BuildConfig.CUSTOMERLY_SOCKET_VERSION)
                                        .toString().getBytes("UTF-8"),
                                        Base64.NO_WRAP), "UTF-8");
                        } catch (JSONException error) {
                            return;
                        } catch (UnsupportedEncodingException e) {
                            return;
                        }
                        /*

                         */
                        try {
                            IO.Options options = new IO.Options();
                            options.secure = true;
                            options.forceNew = true;
                            options.reconnection = false;
//                            options.reconnectionDelay = 15000;
//                            options.reconnectionDelayMax = 60000;
                            options.query = query;

                            socket = IO.socket(String.format("%s:%s/", this.__SOCKET__Endpoint, this.__SOCKET__Port), options);
                            if(socket != null) {
                                this._Socket = socket;
                                this.__SOCKET__CurrentConfiguration = currentConfiguration;
                                socket.on(SOCKET_EVENT__TYPING, payload -> {
                                    if (payload != null && payload.length != 0) {
                                        try {
                                            JSONObject payloadJson = (JSONObject) payload[0];
                                    /*  {   client : {account_id: 82, name: "Gianni"},
                                            conversation : {conversation_id: "173922", account_id: 82, user_id: 55722, is_note: false},
                                            is_typing : "n" } */


                                            //{"conversation":{"conversation_id":"327298","account_id":82,"user_id":310083,"is_note":false},
                                            // "is_typing":"y",
                                            // "client":{"account_id":82,"name":"Gianni"}
                                            JSONObject client = payloadJson.optJSONObject("client");
                                            if (client != null) {
                                                long account_id = client.optLong("account_id", -1L);
                                                if (account_id != -1L) {
                                                    try {
                                                        this._DEV_log(String.format("SOCKET RX: %1$s -> %2$s", SOCKET_EVENT__TYPING, payloadJson.toString(1)));
                                                    } catch (JSONException ignored) {
                                                    }
                                                    boolean is_typing = "y".equals(payloadJson.optString("is_typing"));
                                                    payloadJson = payloadJson.getJSONObject("conversation");
                                                    if (payloadJson != null) {
                                                        IE_JwtToken token2 = this._JwtToken;
                                                        if (token2 != null && token2._UserID != null && token2._UserID == payloadJson.getLong("user_id") && !payloadJson.optBoolean("is_note", false)) {
                                                            long conversation_id = payloadJson.optLong("conversation_id", 0);
                                                            __SOCKET__ITyping_listener listener = this.__SOCKET__Typing_listener;
                                                            if (conversation_id != 0 && listener != null) {
                                                                listener.onTypingEvent(conversation_id, account_id, is_typing);
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (JSONException ignored) {
                                        }
                                    }
                                });
                                socket.on(SOCKET_EVENT__MESSAGE, payload -> {
                                    if (payload != null && payload.length != 0) {
                                        try {
                                            JSONObject payloadJson = (JSONObject) payload[0];
                                    /*
                                    {user_id: 41897, account_id: 82, timestamp: 1483388854, from_account: true,
                                        conversation : {is_note: false} }
                                     */
                                            if (payloadJson.optBoolean("from_account")) {
                                                try {
                                                    this._log("Message received via socket");
                                                    this._DEV_log(String.format("SOCKET RX: %1$s -> %2$s", SOCKET_EVENT__MESSAGE, payloadJson.toString(1)));
                                                } catch (JSONException ignored) {
                                                }
                                                long timestamp = payloadJson.optLong("timestamp", 0L);
                                                long socket_user_id = payloadJson.optLong("user_id", 0L);

                                                IE_JwtToken token2 = this._JwtToken;
                                                if (token2 != null && token2._UserID != null && token2._UserID == socket_user_id
                                                        && socket_user_id != 0 && timestamp != 0
                                                        && !payloadJson.getJSONObject("conversation").optBoolean("is_note", false)) {
                                                    new IApi_Request.Builder<ArrayList<IE_Message>>(IApi_Request.ENDPOINT_MESSAGE_NEWS)
                                                            .opt_converter(data -> IU_Utils.fromJSONdataToList(data, "messages", IE_Message::new))
                                                            .opt_tokenMandatory()
                                                            .opt_receiver((responseState, new_messages) -> {
                                                                if (responseState == IApi_Request.RESPONSE_STATE__OK && new_messages != null && new_messages.size() != 0) {
                                                                    Activity activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                                                    ArrayList<Class<? extends Activity>> disabledActivities = _DisabledActivities;
                                                                    if (activity != null && (disabledActivities == null || ! disabledActivities.contains(activity.getClass()))) {
                                                                        if (activity instanceof SDKActivity) {
                                                                            ((SDKActivity) activity).onNewSocketMessages(new_messages);
                                                                        } else if (this._SupportEnabled) {
                                                                            try {
                                                                                PW_AlertMessage.show(activity, new_messages.get(0));
                                                                            } catch (WindowManager.BadTokenException changedActivityWhileExecuting) {
                                                                                activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                                                                if (activity != null) {
                                                                                    if (activity instanceof SDKActivity) {
                                                                                        ((SDKActivity) activity).onNewSocketMessages(new_messages);
                                                                                    } else {
                                                                                        try {
                                                                                            PW_AlertMessage.show(activity, new_messages.get(0));
                                                                                        } catch (WindowManager.BadTokenException ignored) {
                                                                                            //Second try failure.
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    } else {
                                                                        _PendingRunnableForNotDisabledActivity = () -> __Handler.postDelayed(() -> {
                                                                            Activity p_activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                                                            ArrayList<Class<? extends Activity>> p_disabledActivities = _DisabledActivities;
                                                                            if (p_activity != null && (p_disabledActivities == null || ! p_disabledActivities.contains(p_activity.getClass()))) {
                                                                                if (p_activity instanceof SDKActivity) {
                                                                                    ((SDKActivity) p_activity).onNewSocketMessages(new_messages);
                                                                                } else if (this._SupportEnabled) {
                                                                                    try {
                                                                                        PW_AlertMessage.show(p_activity, new_messages.get(0));
                                                                                    } catch (WindowManager.BadTokenException changedActivityWhileExecuting) {
                                                                                        p_activity = _CurrentActivity == null ? null : _CurrentActivity.get();
                                                                                        if (p_activity != null) {
                                                                                            if (p_activity instanceof SDKActivity) {
                                                                                                ((SDKActivity) p_activity).onNewSocketMessages(new_messages);
                                                                                            } else {
                                                                                                try {
                                                                                                    PW_AlertMessage.show(p_activity, new_messages.get(0));
                                                                                                } catch (WindowManager.BadTokenException ignored) {
                                                                                                    //Second try failure.
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }, 500);//Delay needed or popup is not showed
                                                                    }
                                                                }
                                                            })
                                                            .param("timestamp", timestamp)
                                                            .start();
                                                }
                                            }
                                        } catch (JSONException ignored) {
                                        }
                                    }
                                });

                                final long connectTime = System.currentTimeMillis();
                                socket.on(Socket.EVENT_DISCONNECT, p -> {
                                    if(System.currentTimeMillis() > connectTime + 15000L) {
                                        //Trick to avoid reconnection loop caused by disconnection after connection
                                        this.__SOCKET__check();
                                    }
                                });
                                socket.connect();
                            }
                        } catch (Exception ignored) { }
                    }
                }
            }
        }
    }
    void __SOCKET__check() {
        if(this.__SOCKET__shouldBeConnected) {
            Socket socket = this._Socket;
            if(socket == null || !socket.connected()) {
                this.__SOCKET__connect(null);
            }
        }
    }
    private void __SOCKET__disconnect() {
        this.__SOCKET__shouldBeConnected = false;
        Socket socket = this._Socket;
        if (socket != null) {
            this._Socket = null;
            this.__SOCKET__CurrentConfiguration = null;
	        socket.off();
            socket.disconnect();
        }
    }
    private void __SOCKET__SEND(@NonNull String event, @NonNull JSONObject payloadJson) {
        this.__SOCKET__check();
        Socket socket = this._Socket;
        if(socket != null) {
            try {
                this._DEV_log(String.format("SOCKET TX: %1$s -> %2$s", event, payloadJson.toString(1)));
            } catch (JSONException ignored) {
            }
            socket.emit(event, payloadJson);
        }
    }
    void __SOCKET_SEND_Typing(long pConversationID, boolean pTyping, @Nullable String pText) {
        //{conversation: {conversation_id: 179170, user_id: 63378, is_note: false}, is_typing: "y", typing_preview: "I am writ"}
        IE_JwtToken token = this._JwtToken;
        if(token != null && token._UserID != null) {
            try {
                this.__SOCKET__SEND(SOCKET_EVENT__TYPING, new JSONObject()
                        .put("conversation", new JSONObject()
                                .put("conversation_id", pConversationID)
                                .put("user_id", token._UserID)
                                .put("is_note", false))
                        .put("is_typing", pTyping ? "y" : "n")
                        .put("typing_preview", pText));
            } catch (JSONException ignored) { }
        }
    }
    void __SOCKET_SEND_Message(long pTimestamp) {
        if(pTimestamp != -1L) {
            IE_JwtToken token = this._JwtToken;
            if (token != null && token._UserID != null) {
                try {
                    this._log("Message send event sent via socket");
                    this.__SOCKET__SEND(SOCKET_EVENT__MESSAGE, new JSONObject()
                            .put("timestamp", pTimestamp)
                            .put("user_id", token._UserID)
                            .put("conversation", new JSONObject()
                                    .put("is_note", false)));
                } catch (JSONException ignored) { }
            }
        }
    }
    void __SOCKET_SEND_Seen(long pConversationMessageID, long pSeenDate) {
        IE_JwtToken token = this._JwtToken;
        if(token != null && token._UserID != null) {
            try {
                this._log("Message seen event sent via socket");
                this.__SOCKET__SEND(SOCKET_EVENT__SEEN, new JSONObject()
                        .put("conversation", new JSONObject()
                                .put("conversation_message_id", pConversationMessageID)
                                .put("user_id", token._UserID))
                        .put("seen_date", pSeenDate));
            } catch (JSONException ignored) { }
        }
    }

    void _setIsAppInsolvent() {
        this.__isAppInsolvent = true;
    }

    private synchronized void __PING__Start(@Nullable io.customerly.Customerly.Callback pSuccessCallback, @Nullable Callback pFailureCallback) {
        if(this._isConfigured()) {
            //noinspection SpellCheckingInspection
            new IApi_Request.Builder<Void>(IApi_Request.ENDPOINT_PING)
                    .opt_converter(this.__PING__response_converter__SurveyMessage)
                    .opt_receiver((responseState, _null) -> {
                        if (responseState == IApi_Request.RESPONSE_STATE__OK) {
                            if(pSuccessCallback != null) {
                                pSuccessCallback.callback();
                            }
                        } else {
                            if(pFailureCallback != null) {
                                pFailureCallback.callback();
                            }
                        }
                    })
                    .param("email", IU_Utils.getStringSafe(this._SharedPreferences, PREF_CURRENT_EMAIL))
                    .param("user_id", IU_Utils.getStringSafe(this._SharedPreferences, PREF_CURRENT_ID))
                    .param("company", IU_Utils.getStringJSONSafe(this._SharedPreferences, PREF_CURRENT_COMPANY_INFO, false))
                    .start();
        } else {
            if(pFailureCallback != null) {
                pFailureCallback.callback();
            }
        }
    }

    void _TOKEN__update(@NonNull JSONObject obj) {
        @Subst("authB64.payloadB64.checksumB64") String token = obj.optString("token");
        if(token != null) {
            try {
                SharedPreferences prefs = this._SharedPreferences;
                if(prefs != null) {
                    this._JwtToken = new IE_JwtToken(token, prefs);
                } else {
                    this._JwtToken = new IE_JwtToken(token);
                }
            } catch (IllegalArgumentException wrongTokenFormat) {
                this._JwtToken = null;
            }
        }
    }

    /* ****************************************************************************************************************************************************************/
    /* ********************************************************************************************************************************************** Public Methods **/
    /* ****************************************************************************************************************************************************************/

    private void __init(@NonNull Context pApplicationContext) {
        //TODO test connectivity loss

        //useless: Customerly._Instance._AppCacheDir = pApplicationContext.getCacheDir().getPath();
        //APP INFORMATION
        try {
            Customerly._Instance.__PING__DeviceJSON.put("app_name", pApplicationContext.getApplicationInfo().loadLabel(pApplicationContext.getPackageManager()).toString());
        } catch (JSONException | NullPointerException err) {
            try {
                Customerly._Instance.__PING__DeviceJSON.put("app_name", "<Error retrieving the app name>");
            } catch (JSONException ignored) { }
        }
        try {
            Customerly._Instance.__PING__DeviceJSON.put("app_package", pApplicationContext.getPackageName());
        } catch (JSONException | NullPointerException err) {
            try {
                Customerly._Instance.__PING__DeviceJSON.put("app_package", "<Error retrieving the app app_package>");
            } catch (JSONException ignored) { }
        }
        try {
            PackageInfo pinfo = pApplicationContext.getPackageManager().getPackageInfo(pApplicationContext.getPackageName(), 0);
            Customerly._Instance.__PING__DeviceJSON.put("app_version", pinfo.versionName).put("app_build", pinfo.versionCode);
        } catch (JSONException | PackageManager.NameNotFoundException err) {
            try {
                Customerly._Instance.__PING__DeviceJSON.put("app_version", 0);
            } catch (JSONException ignored) { }
        }

        //PREFS
        final SharedPreferences prefs = pApplicationContext.getSharedPreferences(BuildConfig.APPLICATION_ID + ".SharedPreferences", Context.MODE_PRIVATE);
        Customerly._Instance._SharedPreferences = prefs;

        //WIDGET COLOR
        //noinspection SpellCheckingInspection
        Customerly._Instance.__WidgetColor__HardCoded = IU_Utils.getIntSafe(prefs, "CONFIG_HC_WCOLOR", Color.TRANSPARENT);

        Customerly._Instance.__WidgetColor__Fallback =
                Customerly._Instance.__WidgetColor__HardCoded != Color.TRANSPARENT
                        ? Customerly._Instance.__WidgetColor__HardCoded
                        : DEF_WIDGET_COLOR_MALIBU_INT;

        //JWT TOKEN
        Customerly._Instance._JwtToken = IE_JwtToken.from(prefs);

        //PING
        Customerly._Instance.__PING__LAST_widget_color = IU_Utils.getIntSafe(prefs, PREFS_PING_RESPONSE__WIDGET_COLOR, Customerly._Instance.__WidgetColor__Fallback);
        Customerly._Instance.__PING__LAST_widget_background_url = IU_Utils.getStringSafe(prefs, PREFS_PING_RESPONSE__BACKGROUND_THEME_URL);
        Customerly._Instance.__PING__LAST_powered_by = IU_Utils.getBooleanSafe(prefs, PREFS_PING_RESPONSE__POWERED_BY, false);
        Customerly._Instance.__PING__LAST_welcome_message_users = IU_Utils.getStringSafe(prefs, PREFS_PING_RESPONSE__WELCOME_USERS);
        Customerly._Instance.__PING__LAST_welcome_message_visitors = IU_Utils.getStringSafe(prefs, PREFS_PING_RESPONSE__WELCOME_VISITORS);
        Customerly._Instance.__PING__LAST_active_admins = null;

        Customerly._Instance._AppID = IU_Utils.getStringSafe(prefs, "CONFIG_APP_ID");

        IU_NetworkReceiver.registerLollipopNetworkReceiver(pApplicationContext);

        try {
            Method crashlytics_setString = Class.forName("com.crashlytics.android.Crashlytics").getDeclaredMethod("setString", String.class, String.class);
            crashlytics_setString.invoke(null, BuildConfig.APPLICATION_ID + " version:", BuildConfig.VERSION_NAME);
        } catch (Exception ignored) { }

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager mNotificationManager = (NotificationManager) pApplicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if(mNotificationManager != null) {

                NotificationChannel mChannel = new NotificationChannel(
                        Customerly.NOTIFICATION_CHANNEL_ID_DOWNLOAD, // The id of the channel
                        "Attachment download", // The user-visible name of the channel.
                        NotificationManager.IMPORTANCE_DEFAULT);
// Configure the notification channel.
                mChannel.setDescription("Notification of downloaded attachment success");
                mChannel.enableLights(true);
                mChannel.setLightColor(Color.BLUE);
                mChannel.enableVibration(true);
                mChannel.setVibrationPattern(new long[]{0, 300});
                mNotificationManager.createNotificationChannel(mChannel);
            }
        }

        Customerly._Instance.initialized = true;
    }

    /**
     * Call this method to obtain the reference to the Customerly SDK
     * @return The Customerly SDK instance reference
     */
    @NonNull public static Customerly get() {
        if(! Customerly._Instance.initialized) {//Avoid to perform lock if not needed
            synchronized (Customerly.class) {
                if(! Customerly._Instance.initialized) {//After lock we check again to avoid concurrence
                    WeakReference<Activity> activityWeakRef = Customerly._Instance._CurrentActivity;
                    if(activityWeakRef != null) {
                        Activity activity = activityWeakRef.get();
                        if(activity != null) {
                            Customerly._Instance.__init(activity.getApplicationContext());
                        }
                    }
                }
            }
        }
        return Customerly._Instance;
    }

    /**
     * Call this method to configure the SDK indicating the Customerly App ID before accessing it.<br>
     * Call this from your custom Application {@link Application#onCreate()}
     * @param pApplication The application class reference
     * @param pCustomerlyAppID The Customerly App ID found in your Customerly console
     */
    public static void configure(@NonNull Application pApplication, @NonNull String pCustomerlyAppID) {
        Customerly.configure(pApplication, pCustomerlyAppID, Color.TRANSPARENT);
    }

    /**
     * Call this method to configure the SDK indicating the Customerly App ID before accessing it.<br>
     * Call this from your custom Application {@link Application#onCreate()}<br>
     *     <br>
     * You can choose to ignore the widget_color provided by the Customerly web console for the action bar styling in support activities and use an app-local widget_color instead.
     * @param pApplication The application class reference
     * @param pCustomerlyAppID The Customerly App ID found in your Customerly console
     * @param pWidgetColor The custom widget_color. If Color.TRANSPARENT, it will be ignored
     */
    public static void configure(@NonNull Application pApplication, @NonNull String pCustomerlyAppID, @ColorInt int pWidgetColor) {
        Customerly customerly = Customerly._Instance;
        customerly.__init(pApplication.getApplicationContext());
        final SharedPreferences prefs = customerly._SharedPreferences;
        if(prefs != null) {
            //noinspection SpellCheckingInspection
            prefs.edit().putString("CONFIG_APP_ID", pCustomerlyAppID).putInt("CONFIG_HC_WCOLOR", pWidgetColor).apply();
        }

        customerly._AppID = pCustomerlyAppID.trim();

        customerly.__WidgetColor__HardCoded = pWidgetColor;
        customerly.__PING__LAST_widget_color = Customerly._Instance.__WidgetColor__Fallback =
                pWidgetColor == Color.TRANSPARENT
                        ? DEF_WIDGET_COLOR_MALIBU_INT
                        : pWidgetColor;

        pApplication.registerActivityLifecycleCallbacks(customerly.__ActivityLifecycleCallbacks);
    }

    /**
     * Call this method to enable error logging in the Console.
     * Avoid to enable it in release app versions, the suggestion is to pass your.application.package.BuildConfig.DEBUG as parameter
     * @param pVerboseLogging true for enable logging, please pass your.application.package.BuildConfig.DEBUG
     */
    public void setVerboseLogging(boolean pVerboseLogging) {
        this.__VerboseLogging = pVerboseLogging;
    }

    public interface Callback {
        /**
         * Implement this interface to obtain async success or failure response from {@link #registerUser(String)},
         * {@link #setCompany(HashMap)} or {@link #setAttributes(HashMap)}
         */
        void callback();
    }

    public interface Task {
        /**
         * @param successCallback To receive success async response
         * @return The Task itself for method chaining
         */
        @CheckResult @NonNull Task successCallback(@Nullable Callback successCallback);
        /**
         * @param failureCallback To receive failure async response
         * @return The Task itself for method chaining
         */
        @CheckResult @NonNull Task failureCallback(@Nullable Callback failureCallback);
        /**
         * Don't forget to call this method to start the task
         */
        void start();
    }

    private abstract class __Task implements Task{
        @Nullable protected io.customerly.Customerly.Callback successCallback;
        @Nullable protected Callback failureCallback;
        /**
         * @param successCallback To receive success async response
         * @return The Task itself for method chaining
         */
        @CheckResult @NonNull public Task successCallback(@Nullable Callback successCallback) {
            this.successCallback = successCallback;
            return this;
        }
        /**
         * @param failureCallback To receive failure async response
         * @return The Task itself for method chaining
         */
        @CheckResult @NonNull public Task failureCallback(@Nullable Callback failureCallback) {
            this.failureCallback = failureCallback;
            return this;
        }
        /**
         * Don't forget to call this method to start the task
         */
        public final void start() {
            if(_isConfigured()) {
                try {
                    this._executeTask();
                } catch (Exception generic) {
                    _log("A generic error occurred in " + this.getClass().getSimpleName());
                    IEr_CustomerlyErrorHandler.sendError(IEr_CustomerlyErrorHandler.ERROR_CODE__GENERIC, "Generic error in " + this.getClass().getSimpleName(), generic);
                    if(this.failureCallback != null) {
                        this.failureCallback.callback();
                    }
                }
            } else {
                if(this.failureCallback != null) {
                    this.failureCallback.callback();
                }
            }
        }
        protected abstract void _executeTask();
    }

    public final class UpdateTask extends __Task {
        private UpdateTask() {
            super();
        }
        @Override
        protected void _executeTask() {
            if (System.currentTimeMillis() > __PING__next_ping_allowed) {
                _log("Customerly.update task started");
                __PING__Start(() -> {
                    _log("Customerly.update task completed successfully");
                    if(this.successCallback != null) {
                        this.successCallback.callback();
                    }
                }, () -> {
                    _log("A generic error occurred in Customerly.update");
                    if(this.failureCallback != null) {
                        this.failureCallback.callback();
                    }
                });
            } else {
                _log("You cannot call twice the update so fast. You have to wait " + (__PING__next_ping_allowed - System.currentTimeMillis()) / 1000 + " seconds.");
                if(this.failureCallback != null) {
                    this.failureCallback.callback();
                }
            }
        }
    }

    public final class RegisterUserTask extends __Task {
        @NonNull private final String email;
        @Nullable private String user_id, name;
        @Nullable private JSONObject attributes, company;

        private RegisterUserTask(@NonNull String email) {
            super();
            this.email = email.trim();
        }
        /**
         * Optionally you can specify the user ID
         * @param user_id The ID of the user
         * @return The Task itself for method chaining
         */
        @CheckResult @NonNull public RegisterUserTask user_id(@Nullable String user_id) {
            if(user_id != null) {
                user_id = user_id.trim();
                if(user_id.length() != 0) {
                    this.user_id = user_id;
                }
            } else {
                this.user_id = null;
            }
            return this;
        }
        /**
         * Optionally you can specify the user name
         * @param name The name of the user
         * @return The Task itself for method chaining
         */
        @CheckResult @NonNull public RegisterUserTask name(@Nullable String name) {
            if(name != null) {
                name = name.trim();
                if (name.length() != 0) {
                    this.name = name;
                }
            } else {
                this.name = null;
            }
            return this;
        }
        /**
         * Optionally you can specify the user attributes
         * @param pAttributes The attributes of the user. Can contain only String, char, byte, int, long, float or double values
         * @return The Task itself for method chaining
         * @throws IllegalArgumentException if the attributes map check fails
         */
        @CheckResult @NonNull public RegisterUserTask attributes(@Nullable HashMap<String,Object> pAttributes) throws IllegalArgumentException {
            if(pAttributes != null) {
                Collection<Object> attrs = pAttributes.values();
                for (Object attr : attrs) {
                    if (attr instanceof String ||
                            attr instanceof Integer ||
                            attr instanceof Byte ||
                            attr instanceof Long ||
                            attr instanceof Double ||
                            attr instanceof Float ||
                            attr instanceof Character ||
                            attr instanceof Boolean) {
                        continue;
                    }
                    _log("Attributes HashMap can contain only String, char, byte, int, long, float or double values");
                    throw new IllegalArgumentException("Attributes HashMap can contain only Strings, int, float, long, double or char values");
                }
                this.attributes = new JSONObject(pAttributes);
            } else {
                this.attributes = null;
            }
            return this;
        }
        /**
         * Optionally you can specify the user attributes
         * @param pAttributes The attributes of the user. Can contain only String, char, byte, int, long, float or double values
         * @return The Task itself for method chaining
         * @throws IllegalArgumentException if the attributes map check fails
         */
        @CheckResult @NonNull public RegisterUserTask attributes(@Nullable JSONObject pAttributes) throws IllegalArgumentException {
            if(pAttributes != null) {
                Iterator<String> keysIterator = pAttributes.keys();
                String key;
                while(keysIterator.hasNext()) {
                    Object attr = pAttributes.opt(keysIterator.next());
                    if(     attr != null && (
                            attr instanceof String ||
                            attr instanceof Integer ||
                            attr instanceof Byte ||
                            attr instanceof Long ||
                            attr instanceof Double ||
                            attr instanceof Float ||
                            attr instanceof Character ||
                            attr instanceof Boolean)) {
                        continue;
                    }
                    _log("Attributes HashMap can contain only String, char, byte, int, long, float or double values");
                    throw new IllegalArgumentException("Attributes HashMap can contain only Strings, int, float, long, double or char values");
                }
            } else {
                this.attributes = null;
            }
            return this;
        }

        /**
         * Optionally you can specify the user company
         * @param pCompany The company of the user. The map must contain a String value with key "company_id" containing to the Company ID and a String value with key "name" containing the Company name. Can contain only String, char, int, long, float or double values
         * @return The Task itself for method chaining
         * @throws IllegalArgumentException if the company map check fails
         */
        @CheckResult @NonNull public RegisterUserTask company(@Nullable HashMap<String,Object> pCompany) throws IllegalArgumentException{
            if(pCompany != null) {
                Collection<Object> attrs = pCompany.values();
                for(Object attr : attrs) {
                    if(     attr instanceof String ||
                            attr instanceof Integer ||
                            attr instanceof Byte ||
                            attr instanceof Long ||
                            attr instanceof Double ||
                            attr instanceof Float ||
                            attr instanceof Character ||
                            attr instanceof Boolean) {
                        continue;
                    }
                    _log("Company HashMap can contain only String, char, byte, int, long, float or double values");
                    throw new IllegalArgumentException("Company HashMap can contain only String, char, byte, int, long, float or double values");
                }
                if(! pCompany.containsKey("company_id") && ! pCompany.containsKey("name")) {
                    _log("Company HashMap must contain a String value with key \"company_id\" containing to the Company ID and a String value with key \"name\" containing the Company name");
                    throw new IllegalArgumentException(
                            "Company HashMap must contain a String value with key \"company_id\" containing to the Company ID and a String value with key \"name\" containing the Company name"
                    );
                }
                this.company = new JSONObject(pCompany);
            } else {
                this.company = null;
            }
            return this;
        }

        /**
         * Optionally you can specify the user company
         * @param pCompany The company of the user. The map must contain a String value with key "company_id" containing to the Company ID and a String value with key "name" containing the Company name. Can contain only String, char, int, long, float or double values
         * @return The Task itself for method chaining
         * @throws IllegalArgumentException if the company map check fails
         */
        @CheckResult @NonNull public RegisterUserTask company(@Nullable JSONObject pCompany) throws IllegalArgumentException {
            if(pCompany != null) {
                Iterator<String> keysIterator = pCompany.keys();
                String key;
                while(keysIterator.hasNext()) {
                    Object attr = pCompany.opt(keysIterator.next());
                    if(     attr != null && (
                            attr instanceof String ||
                                    attr instanceof Integer ||
                                    attr instanceof Byte ||
                                    attr instanceof Long ||
                                    attr instanceof Double ||
                                    attr instanceof Float ||
                                    attr instanceof Character ||
                                    attr instanceof Boolean)) {
                        continue;
                    }
                    _log("Company HashMap can contain only String, char, byte, int, long, float or double values");
                    throw new IllegalArgumentException("Company HashMap can contain only String, char, byte, int, long, float or double values");
                }
                if(! pCompany.has("company_id") && ! pCompany.has("name")) {
                    _log("Company HashMap must contain a String value with key \"company_id\" containing to the Company ID and a String value with key \"name\" containing the Company name");
                    throw new IllegalArgumentException(
                            "Company HashMap must contain a String value with key \"company_id\" containing to the Company ID and a String value with key \"name\" containing the Company name"
                    );
                }
                this.company = pCompany;
            } else {
                this.company = null;
            }
            return this;
        }

        protected void _executeTask() {
            SharedPreferences pref = _SharedPreferences;
            if(pref != null && Patterns.EMAIL_ADDRESS.matcher(this.email).matches()) {
                new IApi_Request.Builder<Void>(IApi_Request.ENDPOINT_PING)
                        .opt_converter(root -> {
                            SharedPreferences.Editor editor = pref.edit()
                                    .putString(PREF_CURRENT_EMAIL, this.email)
                                    .putString(PREF_CURRENT_ID, this.user_id);
                            if(this.company != null) {
                                try {
                                    editor.putString(PREF_CURRENT_COMPANY_INFO,
                                            new JSONObject()
                                                    .put("company_id", this.company.getString("company_id"))
                                                    .put("name", this.company.getString("name"))
                                                    .toString());
                                } catch (JSONException ignored) { }
                            } else {
                                editor.remove(PREF_CURRENT_COMPANY_INFO).apply();
                            }
                            editor.apply();
                            return __PING__response_converter__Message.convert(root);
                        })
                        .opt_receiver((responseState, _void) -> {
                            if (responseState == IApi_Request.RESPONSE_STATE__OK) {
                                if(this.successCallback != null) {
                                    _log("Customerly.registerUser task completed successfully");
                                    this.successCallback.callback();
                                }
                            } else {
                                _log("A generic error occurred in Customerly.registerUser task");
                                if(this.failureCallback != null) {
                                    this.failureCallback.callback();
                                }
                            }
                        })

                        .param("email", this.email)
                        .param("user_id", this.user_id)
                        .param("name", this.name)

                        .param("attributes", this.attributes)
                        .param("company", this.company)

                        .start();
                _log("Customerly.registerUser task started");
            } else {
                _log("A generic error occurred in Customerly.registerUser task");
                if(this.failureCallback != null) {
                    this.failureCallback.callback();
                }
            }
        }
    }

    public final class SetAttributesTask extends __Task {
        @NonNull private final JSONObject attributes;
        /**
         * @param attributes The attributes of the user. Can contain only String, char, byte, int, long, float or double values
         * @throws IllegalArgumentException is thrown if the attributes check fails
         */
        private SetAttributesTask (@NonNull HashMap<String,Object> attributes) throws IllegalArgumentException {
            Collection<Object> attrs = attributes.values();
            for(Object attr : attrs) {
                if(     attr instanceof String ||
                        attr instanceof Integer ||
                        attr instanceof Byte ||
                        attr instanceof Long ||
                        attr instanceof Double ||
                        attr instanceof Float ||
                        attr instanceof Character ||
                        attr instanceof Boolean) {
                    continue;
                }
                _log("Attributes HashMap can contain only String, char, byte, int, long, float or double values");
                throw new IllegalArgumentException("Attributes HashMap can contain only Strings, int, float, long, double or char values");
            }
            this.attributes = new JSONObject(attributes);
        }
        /**
         * @param pAttributes The attributes of the user. Can contain only String, char, byte, int, long, float or double values
         * @throws IllegalArgumentException is thrown if the attributes check fails
         */
        private SetAttributesTask (@NonNull JSONObject pAttributes) throws IllegalArgumentException {
            Iterator<String> keysIterator = pAttributes.keys();
            String key;
            while(keysIterator.hasNext()) {
                Object attr = pAttributes.opt(keysIterator.next());
                if(     attr != null && (
                        attr instanceof String ||
                                attr instanceof Integer ||
                                attr instanceof Byte ||
                                attr instanceof Long ||
                                attr instanceof Double ||
                                attr instanceof Float ||
                                attr instanceof Character ||
                                attr instanceof Boolean)) {
                    continue;
                }
                _log("Attributes HashMap can contain only String, char, byte, int, long, float or double values");
                throw new IllegalArgumentException("Attributes HashMap can contain only Strings, int, float, long, double or char values");
            }
            this.attributes = pAttributes;
        }
        @Override
        protected void _executeTask() {
            IE_JwtToken token = _JwtToken;
            if(token != null && token.isUser()) {
                IApi_Request.Builder<Void> builder = new IApi_Request.Builder<Void>(IApi_Request.ENDPOINT_PING)
                        .opt_converter(__PING__response_converter__NaN)
                        .opt_receiver((responseState, _void) -> {
                            if (responseState == IApi_Request.RESPONSE_STATE__OK) {
                                if (this.successCallback != null) {
                                    _log("Customerly.setAttributes task completed successfully");
                                    this.successCallback.callback();
                                }
                            } else {
                                _log("A generic error occurred in Customerly.setAttributes");
                                if (this.failureCallback != null) {
                                    this.failureCallback.callback();
                                }
                            }
                        })
                        .param("attributes", this.attributes);

                SharedPreferences pref = _SharedPreferences;
                if(pref != null) {
                    builder = builder.param("email", IU_Utils.getStringSafe(pref, PREF_CURRENT_EMAIL))
                            .param("user_id", IU_Utils.getStringSafe(pref, PREF_CURRENT_ID))
                            .param("company", IU_Utils.getStringJSONSafe(pref, PREF_CURRENT_COMPANY_INFO, false));
                }
                _log("Customerly.setCompany task started");
                builder.start();
            } else {
                _log("Cannot setAttributes for lead users");
                if(this.failureCallback != null) {
                    this.failureCallback.callback();
                }
            }
        }
    }

    /**
     * Utility builder for Company Map
     */
    public static class CompanyBuilder {
        @NonNull private final JSONObject company = new JSONObject();
        public CompanyBuilder(@NonNull String company_id, @NonNull String name) {
            super();
            try {
                this.company.put("company_id", company_id);
                this.company.put("name", name);
            } catch (JSONException ignored) { }
        }
        @NonNull @CheckResult public CompanyBuilder put(@NonNull String key, @NonNull String value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public CompanyBuilder put(@NonNull String key, int value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public CompanyBuilder put(@NonNull String key, byte value) {
            return this.put(key, (Object)value);
        }
        @NonNull @CheckResult public CompanyBuilder put(@NonNull String key, long value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public CompanyBuilder put(@NonNull String key, double value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public CompanyBuilder put(@NonNull String key, float value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public CompanyBuilder put(@NonNull String key, char value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public CompanyBuilder put(@NonNull String key, boolean value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult private CompanyBuilder put(@NonNull String key, Object value) {
            if(!("company_id".equals(key) || "name".equals(key))) {
                try {
                    this.company.put(key, value);
                } catch (JSONException ignored) { }
            }
            return this;
        }
        @NonNull public JSONObject build() {
            return this.company;
        }
    }

    /**
     * Utility builder for Attributes Map
     */
    public static class AttributesBuilder {
        @NonNull private final JSONObject attrs = new JSONObject();
        @NonNull @CheckResult public AttributesBuilder put(@NonNull String key, @NonNull String value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public AttributesBuilder put(@NonNull String key, int value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public AttributesBuilder put(@NonNull String key, byte value) {
            return this.put(key, (Object)value);
        }
        @NonNull @CheckResult public AttributesBuilder put(@NonNull String key, long value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public AttributesBuilder put(@NonNull String key, double value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public AttributesBuilder put(@NonNull String key, float value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public AttributesBuilder put(@NonNull String key, char value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult public AttributesBuilder put(@NonNull String key, boolean value) {
            return this.put(key, (Object) value);
        }
        @NonNull @CheckResult private AttributesBuilder put(@NonNull String key, Object value) {
            if(!("company_id".equals(key) || "name".equals(key))) {
                try {
                    this.attrs.put(key, value);
                } catch (JSONException ignored) { }
            }
            return this;
        }
        @NonNull public JSONObject build() {
            return this.attrs;
        }
    }

    public final class SetCompanyTask extends __Task {
        @NonNull private final JSONObject company;
        /**
         * @param pCompany The company of the user. The map must contain a String value with key "company_id" containing to the Company ID and a String value with key "name" containing the Company name. Can contain only String, char, int, long, float or double values.
         * @throws IllegalArgumentException is thrown if company map check fails
         */
        private SetCompanyTask(@NonNull HashMap<String,Object> pCompany) throws IllegalArgumentException {
            Collection<Object> attrs = pCompany.values();
            for(Object attr : attrs) {
                if(     attr instanceof String ||
                        attr instanceof Integer ||
                        attr instanceof Byte ||
                        attr instanceof Long ||
                        attr instanceof Double ||
                        attr instanceof Float ||
                        attr instanceof Character ||
                        attr instanceof Boolean) {
                    continue;
                }
                _log("Company HashMap can contain only String, char, byte, int, long, float or double values");
                throw new IllegalArgumentException("Company HashMap can contain only String, char, byte, int, long, float or double values");
            }
            if(! pCompany.containsKey("company_id") && ! pCompany.containsKey("name")) {
                _log("Company HashMap must contain a String value with key \"company_id\" containing to the Company ID and a String value with key \"name\" containing the Company name");
                throw new IllegalArgumentException(
                        "Company HashMap must contain a String value with key \"company_id\" containing to the Company ID and a String value with key \"name\" containing the Company name"
                );
            }
            this.company = new JSONObject(pCompany);
        }
        /**
         * @param pCompany The company of the user. The map must contain a String value with key "company_id" containing to the Company ID and a String value with key "name" containing the Company name. Can contain only String, char, int, long, float or double values.
         * @throws IllegalArgumentException is thrown if company map check fails
         */
        private SetCompanyTask(@NonNull JSONObject pCompany) throws IllegalArgumentException {
            Iterator<String> keysIterator = pCompany.keys();
            String key;
            while(keysIterator.hasNext()) {
                Object attr = pCompany.opt(keysIterator.next());
                if( attr != null && (
                    attr instanceof String ||
                    attr instanceof Integer ||
                    attr instanceof Byte ||
                    attr instanceof Long ||
                    attr instanceof Double ||
                    attr instanceof Float ||
                    attr instanceof Character ||
                    attr instanceof Boolean)) {
                    continue;
                }
                _log("Company HashMap can contain only String, char, byte, int, long, float or double values");
                throw new IllegalArgumentException("Company HashMap can contain only String, char, byte, int, long, float or double values");
            }
            if(! pCompany.has("company_id") && ! pCompany.has("name")) {
                _log("Company HashMap must contain a String value with key \"company_id\" containing to the Company ID and a String value with key \"name\" containing the Company name");
                throw new IllegalArgumentException(
                        "Company HashMap must contain a String value with key \"company_id\" containing to the Company ID and a String value with key \"name\" containing the Company name"
                );
            }
            this.company = pCompany;
        }
        @Override
        protected void _executeTask() {
            IE_JwtToken token = _JwtToken;
            if(token != null && token.isUser()) {
                try {
                    SharedPreferences pref = _SharedPreferences;
                    if(pref != null) {
                        pref.edit().remove(PREF_CURRENT_COMPANY_INFO).apply();
                    }
                    IApi_Request.Builder<Void> builder = new IApi_Request.Builder<Void>(IApi_Request.ENDPOINT_PING)
                            .opt_converter(__PING__response_converter__NaN)
                            .opt_receiver((responseState, _void) -> {
                                if (responseState == IApi_Request.RESPONSE_STATE__OK) {
                                    if (this.successCallback != null) {
                                        _log("Customerly.setCompany task completed successfully");
                                        this.successCallback.callback();
                                        if(pref != null) {
                                            try {
                                                pref.edit()
                                                        .putString(PREF_CURRENT_COMPANY_INFO,
                                                new JSONObject()
                                                        .put("company_id", this.company.getString("company_id"))
                                                        .put("name", this.company.getString("name"))
                                                        .toString())
                                                        .apply();
                                            } catch (JSONException ignored) { }
                                        }
                                    }
                                } else {
                                    _log("A generic error occurred in Customerly.setCompany");
                                    if (this.failureCallback != null) {
                                        this.failureCallback.callback();
                                    }
                                }
                            })
                            .param("company", this.company);

                    if(pref != null) {
                        builder = builder.param("email", IU_Utils.getStringSafe(pref, PREF_CURRENT_EMAIL))
                                .param("user_id", IU_Utils.getStringSafe(pref, PREF_CURRENT_ID));
                    }

                    _log("Customerly.setCompany task started");
                    builder.start();
                } catch (Exception generic) {
                    _log("A generic error occurred in Customerly.setCompany");
                    IEr_CustomerlyErrorHandler.sendError(IEr_CustomerlyErrorHandler.ERROR_CODE__GENERIC, "Generic error in Customerly.setCompany", generic);
                    if(this.failureCallback != null) {
                        this.failureCallback.callback();
                    }
                }
            } else {
                _log("Cannot setCompany for lead users");
                if(this.failureCallback != null) {
                    this.failureCallback.callback();
                }
            }
        }
    }

    /**
     * Call this method to build a task that force a check for pending Surveys or Message for the current user.<br>
     * <br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     * @return The builded task that has to be started with his method {@link UpdateTask#start()}
     */
    @CheckResult @NonNull public UpdateTask update() {
        return new UpdateTask();
    }

    /**
     * Call this method to build a task that links your app user to the Customerly session.<br>
     * <br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     * @param email The mail address of the user
     * @return The builded task that has to be started with his method {@link RegisterUserTask#start()}
     */
    @CheckResult @NonNull public RegisterUserTask registerUser(@NonNull String email) {
        return new RegisterUserTask(email);
    }

    /**
     * Call this method to build a task that add new custom attributes to the user.<br>
     * <br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     * @param pAttributes Optional attributes for the user. Can contain only String, char, int, long, float or double values
     * @return The builded task that has to be started with his method {@link SetAttributesTask#start()}
     * @throws IllegalArgumentException is thrown if the attributes check fails
     */
    @CheckResult @NonNull public SetAttributesTask setAttributes(@NonNull HashMap<String, Object> pAttributes) throws IllegalArgumentException {
        return new SetAttributesTask(pAttributes);
    }

    /**
     * Call this method to build a task that add new custom attributes to the user.<br>
     * <br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     * @param pAttributes Optional attributes for the user. Can contain only String, char, int, long, float or double values
     * @return The builded task that has to be started with his method {@link SetAttributesTask#start()}
     * @throws IllegalArgumentException is thrown if the attributes check fails
     */
    @CheckResult @NonNull public SetAttributesTask setAttributes(@NonNull JSONObject pAttributes) throws IllegalArgumentException {
        return new SetAttributesTask(pAttributes);
    }

    /**
     * Call this method to build a task that add company attributes to the user.<br><br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     * @param pCompany Optional company for the user. The map must contain a String value with key "company_id" containing to the Company ID and a String value with key "name" containing the Company name
     * @return The builded task that has to be started with his method {@link SetCompanyTask#start()}
     * @throws IllegalArgumentException is thrown if company map check fails
     */
    @CheckResult @NonNull public SetCompanyTask setCompany(@NonNull HashMap<String, Object> pCompany) throws IllegalArgumentException {
        return new SetCompanyTask(pCompany);
    }

    /**
     * Call this method to build a task that add company attributes to the user.<br><br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     * @param pCompany Optional company for the user. The map must contain a String value with key "company_id" containing to the Company ID and a String value with key "name" containing the Company name
     * @return The builded task that has to be started with his method {@link SetCompanyTask#start()}
     * @throws IllegalArgumentException is thrown if company map check fails
     */
    @CheckResult @NonNull public SetCompanyTask setCompany(@NonNull JSONObject pCompany) throws IllegalArgumentException {
        return new SetCompanyTask(pCompany);
    }

    /**
     * Call this method to open the Support Activity.<br>
     * A call to this method will force the enabling if the support logic if it has been previously disabled with {@link #setSupportEnabled(boolean)}
     * <br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     * @param activity The current activity
     */
    public void openSupport(@NonNull Activity activity) {
        if(this._isConfigured()) {
            this.setSupportEnabled(true);
            try {
                activity.startActivity(new Intent(activity, IAct_List.class));
                this._log("Customerly.openSupport completed successfully");
            } catch (Exception generic) {
                this._log("A generic error occurred in Customerly.openSupport");
                IEr_CustomerlyErrorHandler.sendError(IEr_CustomerlyErrorHandler.ERROR_CODE__GENERIC, "Generic error in Customerly.openSupport", generic);
            }
        }
    }

    /**
     * Call this method to close the user's Customerly session.<br>
     * <br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     */
    public void logoutUser() {
        if(this._isConfigured()) {
            try {
                final SharedPreferences prefs = this._SharedPreferences;

                this._JwtToken = null;
                if (prefs != null) {
                    IE_JwtToken.remove(prefs);
                    //noinspection SpellCheckingInspection
                    prefs.edit().remove(PREF_CURRENT_EMAIL).remove(PREF_CURRENT_ID).remove(PREF_CURRENT_COMPANY_INFO).apply();
                }
                this.__SOCKET__disconnect();
                this.__PING__next_ping_allowed = 0L;

                PW_AlertMessage.onUserLogout();
                Activity current = this._CurrentActivity == null ? null : this._CurrentActivity.get();
                if(current != null) {
                    if (current instanceof SDKActivity) {
                        ((SDKActivity) current).onLogoutUser();
                    }
                    IDlgF_Survey.dismiss(current);
                }
                this._log("Customerly.logoutUser completed successfully");
                this.__PING__Start(null, null);
            } catch (Exception ignored) { }
        }
    }

    /**
     * Call this method to keep track of custom labeled events.<br>
     * <br>
     * You have to configure the Customerly SDK before using this method with {@link #configure(Application,String)}
     * @param pEventName The event custom label
     */
    public void trackEvent(@NonNull final String pEventName) {
        if(this._isConfigured() && pEventName.length() != 0) {
            try {
                IE_JwtToken token = this._JwtToken;
                if(token != null && (token.isUser() || token.isLead())) {
                    _log("Customerly.trackEvent task started for event " + pEventName);
                    new IApi_Request.Builder<IE_Message>(IApi_Request.ENDPOINT_EVENT_TRACKING)
                            .opt_trials(2)
                            .param("name", pEventName)
                            .opt_receiver(((pResponseState, pResponse) -> this._log("Customerly.trackEvent completed successfully for event " + pEventName)))
                            .start();
                } else {
                    _log("Can trackEvents only for lead and registered users");
                }
            } catch (Exception generic) {
                this._log("A generic error occurred in Customerly.trackEvent");
                IEr_CustomerlyErrorHandler.sendError(IEr_CustomerlyErrorHandler.ERROR_CODE__GENERIC, "Generic error in Customerly.trackEvent", generic);
            }
        }
    }

    /**
     * Call this method to disable or enable the message receiving. It is ENABLED by default.<br>
     * A call to the method {@link #openSupport(Activity)} will force the enabling if it is disabled
     * @param enabled true if you want to enable it, false otherwise
     */
    public void setSupportEnabled(boolean enabled) {
        if(this._SupportEnabled) {
            if(!enabled) {
                this._SupportEnabled = false;
                this.__SOCKET__disconnect();
            }
        } else {
            if(enabled) {
                this._SupportEnabled = true;
                if(this._isConfigured()) {
                    this.__SOCKET__connect(null);
                }
            }
        }
    }

    /**
     * Call this method to disable or enable the survey receiving. It is ENABLED by default.<br>
     * @param enabled true if you want to enable it, false otherwise
     */
    public void setSurveysEnabled(boolean enabled) {
        this._SurveyEnabled = enabled;
    }

    /**
     * Call this method to specify an Activity that will never display a message popup or survey.<br>
     * Every Activity is ENABLED by default
     * @param activityClass The Activity class
     * @see #enableOn(Class)
     */
    public void disableOn(Class<? extends Activity> activityClass) {
        ArrayList<Class<? extends Activity>> disabledActivities = this._DisabledActivities;
        if(disabledActivities == null) {
            this._DisabledActivities = disabledActivities = new ArrayList<>(1);
        }
        disabledActivities.add(activityClass);
    }

    /**
     * Call this method to re-enable an Activity previously disabled with {@link #disableOn(Class)}.
     * @param activityClass The Activity class
     * @see #disableOn(Class)
     */
    public void enableOn(Class<? extends Activity> activityClass) {
        ArrayList<Class<? extends Activity>> disabledActivities = this._DisabledActivities;
        if(disabledActivities != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                disabledActivities.removeIf(c -> c == activityClass);
            } else {
                disabledActivities.remove(activityClass);
            }
        }
    }

    /**
     * @return Returns true if the SDK is available.
     */
    public boolean isSDKavailable() {
        try {
            return ! this.__isAppInsolvent && Version.valueOf(BuildConfig.VERSION_NAME).greaterThan(Version.valueOf(this.__PING__LAST_min_version));
        } catch (Exception any) {
            return false;
        }
    }
}
