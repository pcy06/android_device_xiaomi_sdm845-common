/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.incallaudiotest;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public final class ToneActivity extends Activity {
    private static final String TAG = "InCallAudioTest";

    private static final int TONE_HZ = 1000;
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int[] PREFERRED_SAMPLE_RATES = {16000, 48000, 8000};
    private static final double TONE_GAIN = 0.12;

    private AudioManager mAudioManager;
    private Handler mMainHandler;
    private TextView mStatusView;
    private TextView mDevicesView;
    private TonePlayer mPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAudioManager = getSystemService(AudioManager.class);
        mMainHandler = new Handler(Looper.getMainLooper());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("In-call Audio Test");
        title.setTextSize(24);
        content.addView(title, matchWrap());

        mStatusView = new TextView(this);
        mStatusView.setTextSize(16);
        mStatusView.setPadding(0, 24, 0, 24);
        content.addView(mStatusView, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refreshDevices());
        row.addView(refresh, weightedButton());

        Button pulse = new Button(this);
        pulse.setText("Play 2s");
        pulse.setOnClickListener(v -> startTone(2000));
        row.addView(pulse, weightedButton());

        Button continuous = new Button(this);
        continuous.setText("Start");
        continuous.setOnClickListener(v -> startTone(0));
        row.addView(continuous, weightedButton());

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopTone("Stopped"));
        row.addView(stop, weightedButton());

        content.addView(row, matchWrap());

        mDevicesView = new TextView(this);
        mDevicesView.setTextSize(14);
        mDevicesView.setPadding(0, 24, 0, 0);
        content.addView(mDevicesView, matchWrap());

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        setContentView(scrollView);

        refreshDevices();
    }

    @Override
    protected void onDestroy() {
        stopTone("Stopped");
        super.onDestroy();
    }

    private void refreshDevices() {
        AudioDeviceInfo telephonyDevice = findTelephonyOutput();
        if (telephonyDevice == null) {
            setStatus("No TYPE_TELEPHONY output. Start a cellular call, then refresh.");
        } else {
            setStatus("Ready: " + describeDevice(telephonyDevice));
        }
        mDevicesView.setText(describeOutputDevices());
    }

    private void startTone(long durationMs) {
        AudioDeviceInfo telephonyDevice = findTelephonyOutput();
        if (telephonyDevice == null) {
            stopTone("No TYPE_TELEPHONY output");
            refreshDevices();
            return;
        }

        stopTone(null);

        int sampleRate = chooseSampleRate(telephonyDevice);
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            setStatus("Invalid AudioTrack buffer size: " + minBufferSize);
            return;
        }

        int bufferSize = Math.max(minBufferSize, sampleRate / 10 * Short.BYTES);
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            setStatus("AudioTrack build failed: " + e.getMessage());
            return;
        }

        boolean preferred = track.setPreferredDevice(telephonyDevice);
        if (!preferred) {
            track.release();
            setStatus("setPreferredDevice(TYPE_TELEPHONY) failed");
            return;
        }

        TonePlayer player = new TonePlayer(track, sampleRate, durationMs);
        if (!player.start()) {
            return;
        }
        mPlayer = player;

        String mode = durationMs > 0 ? "2s" : "continuous";
        setStatus(String.format(Locale.US, "Playing %s %d Hz to %s @ %d Hz",
                mode, TONE_HZ, describeDevice(telephonyDevice), sampleRate));
    }

    private void stopTone(String status) {
        TonePlayer player = mPlayer;
        mPlayer = null;
        if (player != null) {
            player.stop();
        }
        if (status != null) {
            setStatus(status);
        }
    }

    private AudioDeviceInfo findTelephonyOutput() {
        AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
                return device;
            }
        }
        return null;
    }

    private static int chooseSampleRate(AudioDeviceInfo device) {
        int[] sampleRates = device.getSampleRates();
        if (sampleRates == null || sampleRates.length == 0) {
            return DEFAULT_SAMPLE_RATE;
        }
        for (int preferred : PREFERRED_SAMPLE_RATES) {
            for (int rate : sampleRates) {
                if (rate == preferred) {
                    return preferred;
                }
            }
        }
        return sampleRates[0];
    }

    private String describeOutputDevices() {
        StringBuilder builder = new StringBuilder("Output devices:\n");
        AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            builder.append(" - ")
                    .append(typeName(device.getType()))
                    .append(" id=")
                    .append(device.getId())
                    .append(" name=")
                    .append(device.getProductName())
                    .append(" rates=")
                    .append(formatRates(device.getSampleRates()))
                    .append('\n');
        }
        return builder.toString();
    }

    private static String describeDevice(AudioDeviceInfo device) {
        return typeName(device.getType()) + " id=" + device.getId();
    }

    private static String formatRates(int[] rates) {
        if (rates == null || rates.length == 0) {
            return "default";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < rates.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(rates[i]);
        }
        return builder.toString();
    }

    private static String typeName(int type) {
        if (type == AudioDeviceInfo.TYPE_TELEPHONY) {
            return "TYPE_TELEPHONY";
        } else if (type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
            return "TYPE_BUILTIN_EARPIECE";
        } else if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            return "TYPE_BUILTIN_SPEAKER";
        } else if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            return "TYPE_WIRED_HEADSET";
        } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return "TYPE_BLUETOOTH_SCO";
        }
        return "type=" + type;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private void setStatus(String status) {
        Log.i(TAG, status);
        mStatusView.setText(status);
    }

    private final class TonePlayer implements Runnable {
        private final AudioTrack mTrack;
        private final int mSampleRate;
        private final long mDurationMs;
        private final Thread mThread;
        private volatile boolean mRunning = true;

        TonePlayer(AudioTrack track, int sampleRate, long durationMs) {
            mTrack = track;
            mSampleRate = sampleRate;
            mDurationMs = durationMs;
            mThread = new Thread(this, "InCallAudioTone");
        }

        boolean start() {
            mTrack.addOnRoutingChangedListener(new AudioRouting.OnRoutingChangedListener() {
                @Override
                public void onRoutingChanged(AudioRouting router) {
                    AudioDeviceInfo routedDevice = mTrack.getRoutedDevice();
                    String routed = routedDevice == null ? "none" : describeDevice(routedDevice);
                    setStatus("Routed: " + routed);
                }
            }, mMainHandler);
            try {
                mTrack.play();
            } catch (IllegalStateException e) {
                mTrack.release();
                setStatus("AudioTrack play failed: " + e.getMessage());
                return false;
            }
            mThread.start();
            return true;
        }

        void stop() {
            mRunning = false;
            try {
                mTrack.pause();
                mTrack.flush();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Ignoring stop on inactive track", e);
            }
        }

        @Override
        public void run() {
            short[] samples = new short[Math.max(160, mSampleRate / 50)];
            double phase = 0.0;
            double step = 2.0 * Math.PI * TONE_HZ / mSampleRate;
            long endMs = mDurationMs > 0 ? System.currentTimeMillis() + mDurationMs : Long.MAX_VALUE;

            try {
                while (mRunning && System.currentTimeMillis() < endMs) {
                    for (int i = 0; i < samples.length; i++) {
                        samples[i] = (short) (Math.sin(phase) * Short.MAX_VALUE * TONE_GAIN);
                        phase += step;
                        if (phase > 2.0 * Math.PI) {
                            phase -= 2.0 * Math.PI;
                        }
                    }
                    mTrack.write(samples, 0, samples.length);
                }
            } finally {
                try {
                    mTrack.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Ignoring stop on released track", e);
                }
                mTrack.release();
                mMainHandler.post(() -> {
                    if (mPlayer == this) {
                        mPlayer = null;
                        setStatus("Tone finished");
                    }
                });
            }
        }
    }
}
