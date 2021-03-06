/*
 * Copyright (C) 2011 Warren Chu
 * Copyright (C) 2008 The Android Open Source Project
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

package com.zhaoshouren.android.apps.clock.service;

import static com.zhaoshouren.android.apps.clock.DeskClock.DEVELOPER_MODE;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Vibrator;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.zhaoshouren.android.apps.clock.DeskClock;


import com.zhaoshouren.android.apps.clock.provider.AlarmContract.Key;
import com.zhaoshouren.android.apps.clock.util.Action;
import com.zhaoshouren.android.apps.clock.util.Alarm;
import com.zhaoshouren.android.apps.clock.R;

/**
 * Manages alarms and vibe. Runs as a service so that it can continue to play if another activity
 * overrides the AlarmAlert dialog.
 */
public class AlarmPlayerService extends Service {

    public static final String TAG = "ZS.AlarmPlayerZervice";

    /** Play alarm up to 10 minutes before silencing */
    private static final int ALARM_TIMEOUT_SECONDS = 10 * 60;

    // Volume suggested by media team for in-call alarms.
    private static final float IN_CALL_VOLUME = 0.125f;

    private static final long[] VIBRATE_PATTERN = new long[] { 500, 500 };

    private boolean mPlaying = false;
    private Vibrator mVibrator;
    private MediaPlayer mMediaPlayer = null;
    private Alarm mAlarm;
    private long mStartTime;
    private TelephonyManager mTelephonyManager;
    private int mInitialCallState;

    // Internal messages
    private static final int KILLER = 1000;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(final Message message) {
            if (message.what == KILLER) {
                if (DEVELOPER_MODE) {
                    Log.d(TAG, "mHandler.handleMessage(): Alarm killer triggered");
                }

                sendKillBroadcast((Alarm) message.obj);
                stopSelf();
            }
        }
    };

    private final PhoneStateListener phoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(final int state, final String ignored) {
            // The user might already be in a call when the alarm fires. When
            // we register onCallStateChanged, we get the initial in-call state
            // which kills the alarm. Check against the initial call state so
            // we don't kill the alarm during a call.
            if (state != TelephonyManager.CALL_STATE_IDLE && state != mInitialCallState) {
                sendKillBroadcast(mAlarm);
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Listen for incoming calls to kill the alarm.
        mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mTelephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        DeskClock.acquireWakeLock(this);
    }

    @Override
    public void onDestroy() {
        stopPlaying();

        // Stop listening for incoming calls.
        mTelephonyManager.listen(phoneStateListener, 0);

        DeskClock.releaseWakeLock();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();

        if (action == Action.PLAY) {
            if (mPlaying) {
                stopPlaying();
            }

            if (mMediaPlayer == null) {
                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setOnErrorListener(new OnErrorListener() {
                    @Override
                    public boolean onError(final MediaPlayer mediaPlayer, final int what,
                            final int extra) {
                        Log.e(TAG,
                                "AlarmPlayerService>mMediaPlayer.setOnErrorListener>OnErrorListener.onError(): Error occurred while playing audio.");
                        mediaPlayer.stop();
                        mediaPlayer.release();
                        mMediaPlayer = null;
                        return true;
                    }
                });
            }

            mAlarm = intent.getParcelableExtra(Alarm.Keys.PARCELABLE);
            startPlaying(mAlarm);

            // Record the initial call state here so that the new alarm has the
            // newest state.
            mInitialCallState = mTelephonyManager.getCallState();

            return START_REDELIVER_INTENT;

        } else if (action == Action.STOP) {
            if (mAlarm != null) {
                sendKillBroadcast(mAlarm);
            }
        }

        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void sendKillBroadcast(final Alarm alarm) {
        sendBroadcast(new Intent(Action.KILLED).putExtra(Alarm.Keys.PARCELABLE, alarm).putExtra(
                Key.ALARM_KILLED_TIMEOUT,
                (int) Math.round((System.currentTimeMillis() - mStartTime) / 60000.0)));
    }

    private void startPlaying(final Alarm alarm) {
        if (DEVELOPER_MODE) {
            Log.d(TAG, "startPlaying()" + "\n    alarm.id = " + alarm.id
                    + "\n    alarm.ringtoneUri = " + alarm.ringtoneUri);
        }

        if (!(alarm.ringtoneUri == null || alarm.ringtoneUri == Uri.EMPTY)) {
            try {
                // Check if we are in a call. If we are, use the in-call alarm
                // resource at a low volume to not disrupt the call.
                if (mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                    if (DEVELOPER_MODE) {
                        Log.d(TAG, "startPlaying(): Using the in-call alarm");
                    }
                    mMediaPlayer.setVolume(IN_CALL_VOLUME, IN_CALL_VOLUME);
                    setDataSourceFromResource(getResources(), mMediaPlayer, R.raw.in_call_alarm);
                } else {
                    mMediaPlayer.setDataSource(this, alarm.ringtoneUri);
                }
                startAlarm(mMediaPlayer);
            } catch (final Exception exception) {
                Log.i(TAG, "AlarmPlayerService.play(): Using the fallback ringtone");
                // The alert may be on the sd card which could be busy right
                // now. Use the fallback ringtone.
                try {
                    // Must reset the media player to clear the error state.
                    mMediaPlayer.reset();
                    setDataSourceFromResource(getResources(), mMediaPlayer, R.raw.fallbackring);
                    startAlarm(mMediaPlayer);
                } catch (final Exception anotherException) {
                    // At this point we just don't play anything.
                    Log.e(TAG, "AlarmPlayerService.play(): Failed to play fallback ringtone",
                            anotherException);
                }
            }
        }

        /* Start the vibrator after everything is ok with the media player */
        if (alarm.vibrate) {
            mVibrator.vibrate(VIBRATE_PATTERN, 0);
        } else {
            mVibrator.cancel();
        }

        mHandler.sendMessageDelayed(mHandler.obtainMessage(KILLER, alarm),
                1000 * ALARM_TIMEOUT_SECONDS);
        mPlaying = true;
        mStartTime = System.currentTimeMillis();
    }

    // Do the common stuff when starting the alarm.
    private void startAlarm(final MediaPlayer mediaPlayer) throws java.io.IOException,
            IllegalArgumentException, IllegalStateException {
        final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // do not play alarms if stream volume is 0
        // (typically because ringer mode is silent).
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        }
    }

    private void setDataSourceFromResource(final Resources resources, final MediaPlayer player,
            final int res) throws java.io.IOException {
        final AssetFileDescriptor assetFileDescriptor = resources.openRawResourceFd(res);
        if (assetFileDescriptor != null) {
            player.setDataSource(assetFileDescriptor.getFileDescriptor(),
                    assetFileDescriptor.getStartOffset(), assetFileDescriptor.getLength());
            assetFileDescriptor.close();
        }
    }

    /**
     * Stops alarm audio and disables alarm if it not snoozed and not repeating
     */
    public void stopPlaying() {
        if (DEVELOPER_MODE) {
            Log.d(TAG, "stopPlaying()");
        }

        // Stop audio playing
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        // Stop vibrator
        mVibrator.cancel();
        mHandler.removeMessages(KILLER);

        sendBroadcast(new Intent(Action.DONE));
        mPlaying = false;
    }
}
