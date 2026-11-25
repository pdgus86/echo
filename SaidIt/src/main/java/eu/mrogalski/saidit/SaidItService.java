package eu.mrogalski.saidit;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import simplesound.pcm.WavAudioFormat;
import simplesound.pcm.WavFileWriter;
import static eu.mrogalski.saidit.SaidIt.*;

public class SaidItService extends Service {
    static final String TAG = SaidItService.class.getSimpleName();
    private static final int FOREGROUND_NOTIFICATION_ID = 458;
    private static final String YOUR_NOTIFICATION_CHANNEL_ID = "SaidItServiceChannel";

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;


    File wavFile;
    AudioRecord audioRecord; // used only in the audio thread
    WavFileWriter wavFileWriter; // used only in the audio thread
    final AudioMemory audioMemory = new AudioMemory(); // used only in the audio thread

    HandlerThread audioThread;
    Handler audioHandler; // used to post messages to audio thread
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Actions sent by the home-screen widget (see EchoWidgetProvider).
    static final String ACTION_WIDGET_START = "eu.mrogalski.saidit.action.WIDGET_START";
    static final String ACTION_WIDGET_PAUSE = "eu.mrogalski.saidit.action.WIDGET_PAUSE";
    static final String ACTION_WIDGET_STOP = "eu.mrogalski.saidit.action.WIDGET_STOP";
    static final String ACTION_WIDGET_SAVE = "eu.mrogalski.saidit.action.WIDGET_SAVE";
    static final String ACTION_WIDGET_DELETE = "eu.mrogalski.saidit.action.WIDGET_DELETE";

    // Second tap on Delete must arrive within this window, or the first tap is ignored.
    private static final long DELETE_CONFIRM_WINDOW_MS = 3000;
    private long deleteConfirmUntil = 0;

    // Save "everything in memory" (clamped to what is actually buffered).
    static final float ALL_MEMORY_SECONDS = 60 * 60 * 24 * 365;

    // While paused, audio coming from the microphone is read and thrown away:
    // it is neither written to the current recording file nor kept in memory.
    // `paused` is only changed on the audio thread; `pauseRequested` is the main-thread view of it.
    volatile boolean paused = false;
    boolean pauseRequested = false;

    @Override
    public void onCreate() {

        Log.d(TAG, "Reading native sample rate");

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        SAMPLE_RATE = preferences.getInt(SAMPLE_RATE_KEY, AudioTrack.getNativeOutputSampleRate (AudioManager.STREAM_MUSIC));
        Log.d(TAG, "Sample rate: " + SAMPLE_RATE);
        FILL_RATE = 2 * SAMPLE_RATE;

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStartListening();
        }

    }

    @Override
    public void onDestroy() {
        stopRecording(null, "");
        innerStopListening();
        stopForeground(true);
        pauseRequested = false;
        audioHandler.post(() -> paused = false);
        state = STATE_READY;
        publishState();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new BackgroundRecorderBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    public void enableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, true).commit();

        innerStartListening();
    }

    public void disableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, false).commit();

        innerStopListening();
    }

    int state;

    static final int STATE_READY = 0;
    static final int STATE_LISTENING = 1;
    static final int STATE_RECORDING = 2;

    private void innerStartListening() {
        switch(state) {
            case STATE_READY:
                break;
            case STATE_LISTENING:
            case STATE_RECORDING:
                return;
        }
        state = STATE_LISTENING;

        Log.d(TAG, "Queueing: START LISTENING");

        startService(new Intent(this, this.getClass()));

        final long memorySize = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getLong(AUDIO_MEMORY_SIZE_KEY, Runtime.getRuntime().maxMemory() / 4);

        audioHandler.post(new Runnable() {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                Log.d(TAG, "Executing: START LISTENING");
                Log.d(TAG, "Audio: INITIALIZING AUDIO_RECORD");

                audioRecord = new AudioRecord(
                       MediaRecorder.AudioSource.MIC,
                       SAMPLE_RATE,
                       AudioFormat.CHANNEL_IN_MONO,
                       AudioFormat.ENCODING_PCM_16BIT,
                       AudioMemory.CHUNK_SIZE);

                if(audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Audio: INITIALIZATION ERROR - releasing resources");
                    audioRecord.release();
                    audioRecord = null;
                    state = STATE_READY;
                    publishState();
                    return;
                }

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioMemory.allocate(memorySize);

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioRecord.startRecording();
                audioHandler.post(audioReader);
            }
        });
        publishState();

    }

    private void innerStopListening() {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }
        state = STATE_READY;
        Log.d(TAG, "Queueing: STOP LISTENING");
        if (pauseRequested) setPaused(false);

        stopForeground(true);
        stopService(new Intent(this, this.getClass()));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: STOP LISTENING");
                if(audioRecord != null)
                    audioRecord.release();
                audioHandler.removeCallbacks(audioReader);
                audioMemory.allocate(0);
            }
        });
        publishState();

    }

    public void dumpRecording(final float memorySeconds, final WavFileReceiver wavFileReceiver, String newFileName) {
        if(state != STATE_LISTENING) throw new IllegalStateException("Not listening!");

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependBytes = (int)(memorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();

                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                int useBytes = bytesAvailable - skipBytes;
                if(useBytes <= 0) {
                    mainHandler.post(() -> showToast(getString(R.string.nothing_to_save)));
                    return;
                }
                long millis  = System.currentTimeMillis() - 1000L * useBytes / FILL_RATE;
                String filename = timestampedFileName(millis);
                if(newFileName != null && !newFileName.trim().equals("")){
                    filename = newFileName.trim() + ".wav";
                }

                File storageDir;
                if(isExternalStorageWritable()){
                    // Use public storage directory for Android 11+ (min SDK 30)
                    storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Echo");
                }else{
                    storageDir = new File(getFilesDir(), "Echo");
                }

                if(!storageDir.exists()){
                    storageDir.mkdir();
                }
                File file = new File(storageDir, filename);
                if(file.exists() && filename.startsWith("Echo_")) {
                    // Two saves in the same second: keep both instead of overwriting.
                    String base = filename.substring(0, filename.length() - ".wav".length());
                    for(int n = 2; file.exists(); n++) {
                        file = new File(storageDir, base + "_" + n + ".wav");
                    }
                }

                // Create the file if it doesn't exist
                if (!file.exists()) {
                    try {
                        if (!file.createNewFile()) {
                            // Handle file creation failure
                            throw new IOException("Failed to create file");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        // Handle IOException
                        showToast(getString(R.string.cant_create_file) + file.getAbsolutePath());
                    }
                }
                final WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try (WavFileWriter writer = new WavFileWriter(format, file)) {
                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override
                            public int consume(byte[] array, int offset, int count) throws IOException {
                                writer.write(array, offset, count);
                                return 0;
                            }
                        });
                    } catch (IOException e) {
                        // Handle error during file writing
                        showToast(getString(R.string.error_during_writing_history_into) + file.getAbsolutePath());
                        Log.e(TAG, "Error during writing history into " + file.getAbsolutePath(), e);
                    }
                    if (wavFileReceiver != null) {
                        wavFileReceiver.fileReady(file, writer.getTotalSampleBytesWritten() * getBytesToSeconds());
                    }
                } catch (IOException e) {
                    // Handle error during file creation or closing writer
                    showToast(getString(R.string.cant_create_file) + file.getAbsolutePath());
                    Log.e(TAG, "Can't create file " + file.getAbsolutePath(), e);
                }
            }
        });

    }
    private static boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }
    private void showToast(String message) {
        Toast.makeText(SaidItService.this, message, Toast.LENGTH_LONG).show();
    }

    public void startRecording(final float prependedMemorySeconds) {
        switch(state) {
            case STATE_READY:
                innerStartListening();
                break;
            case STATE_LISTENING:
                break;
            case STATE_RECORDING:
                return;
        }
        state = STATE_RECORDING;
        // Starting a new recording (from the app or the widget) always un-pauses.
        if (pauseRequested) setPaused(false);

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                int prependBytes = (int)(prependedMemorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();

                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                int useBytes = bytesAvailable - skipBytes;
                long millis  = System.currentTimeMillis() - 1000L * useBytes / FILL_RATE;
                String filename = timestampedFileName(millis);

                File storageDir;
                if(isExternalStorageWritable()){
                    // Use public storage directory for Android 11+ (min SDK 30)
                    storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Echo");
                }else{
                    storageDir = new File(getFilesDir(), "Echo");
                }
                if(!storageDir.exists()){
                    storageDir.mkdirs();
                }
                final String storagePath = storageDir.getAbsolutePath();

                String path = storagePath + "/" + filename;

                wavFile = new File(path);
                try {
                    wavFile.createNewFile();
                } catch (IOException e) {
                    filename = filename.replace(':', '.');
                    path = storagePath + "/" + filename;
                    wavFile = new File(path);
                }
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    wavFileWriter = new WavFileWriter(format, wavFile);
                } catch (IOException e) {
                    final String errorMessage = getString(R.string.cant_create_file) + path;
                    Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                    Log.e(TAG, errorMessage, e);
                    return;
                }

                final String finalPath = path;

                if(skipBytes < bytesAvailable) {
                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override
                            public int consume(byte[] array, int offset, int count) throws IOException {
                                wavFileWriter.write(array, offset, count);
                                return 0;
                            }
                        });
                    } catch (IOException e) {
                        final String errorMessage = getString(R.string.error_during_writing_history_into) + finalPath;
                        Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                        Log.e(TAG, errorMessage, e);
                        stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
                    }
                }
            }
        });
        publishState();
    }

    public long getMemorySize() {
        return audioMemory.getAllocatedMemorySize();
    }

    public void setMemorySize(final long memorySize) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putLong(AUDIO_MEMORY_SIZE_KEY, memorySize).commit();

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            audioHandler.post(new Runnable() {
                @Override
                public void run() {
                    audioMemory.allocate(memorySize);
                }
            });
        }
    }

    public int getSamplingRate() {
        return SAMPLE_RATE;
    }

    public void setSampleRate(int sampleRate) {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putInt(SAMPLE_RATE_KEY, sampleRate).commit();

        innerStopListening();
        SAMPLE_RATE = sampleRate;
        FILL_RATE = 2 * SAMPLE_RATE;
        innerStartListening();
    }

    public interface WavFileReceiver {
        public void fileReady(File file, float runtime);
    }

    public void stopRecording(final WavFileReceiver wavFileReceiver, String newFileName) {
        switch(state) {
            case STATE_READY:
            case STATE_LISTENING:
                return;
            case STATE_RECORDING:
                break;
        }
        state = STATE_LISTENING;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                try {
                    wavFileWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "CLOSING ERROR", e);
                }
                if(wavFileReceiver != null) {
                    wavFileReceiver.fileReady(wavFile, wavFileWriter.getTotalSampleBytesWritten() * getBytesToSeconds());
                }
                wavFileWriter = null;
            }
        });

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if(!preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStopListening();
        }

        stopForeground(true);
        publishState();
    }

    private void flushAudioRecord() {
        // Only allowed on the audio thread
        assert audioHandler.getLooper() == Looper.myLooper();
        audioHandler.removeCallbacks(audioReader); // remove any delayed callbacks
        audioReader.run();
    }

    final AudioMemory.Consumer filler = new AudioMemory.Consumer() {
        @Override
        public int consume(final byte[] array, final int offset, final int count) throws IOException {
//            Log.d(TAG, "READING " + count + " B");
            final int read = audioRecord.read(array, offset, count, AudioRecord.READ_NON_BLOCKING);
            if (read == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AUDIO RECORD ERROR - BAD VALUE");
                return 0;
            }
            if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "AUDIO RECORD ERROR - INVALID OPERATION");
                return 0;
            }
            if (read == AudioRecord.ERROR) {
                Log.e(TAG, "AUDIO RECORD ERROR - UNKNOWN ERROR");
                return 0;
            }
            final boolean discard = paused;
            if (!discard && wavFileWriter != null && read > 0) {
                wavFileWriter.write(array, offset, read);
            }
            if (read == count) {
                // We've filled the buffer, so let's read again.
                audioHandler.post(audioReader);
            } else {
                // It seems we've read everything!
                //
                // Estimate how long do we have until audioRecord fills up completely and post the callback 1 second before that
                // (but not earlier than half the buffer and no later than 90% of the buffer).
                float bufferSizeInSeconds = audioRecord.getBufferSizeInFrames() / (float)SAMPLE_RATE;
                float delaySeconds = bufferSizeInSeconds - 1;
                delaySeconds = Math.max(delaySeconds, bufferSizeInSeconds * 0.5f);
                delaySeconds = Math.min(delaySeconds, bufferSizeInSeconds * 0.9f);
                audioHandler.postDelayed(audioReader, (long)(delaySeconds * 1000));
            }
            // When paused, report that nothing was stored so the bytes just read get overwritten.
            return discard ? 0 : Math.max(read, 0);
        }
    };
    final Runnable audioReader = new Runnable() {
        @Override
        public void run() {
            try {
                audioMemory.fill(filler);
            } catch (IOException e) {
                final String errorMessage = getString(R.string.error_during_recording_into) + wavFile.getName();
                Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                Log.e(TAG, errorMessage, e);
                stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this), "");
            }
        }
    };

    public interface StateCallback {
        public void state(boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded);
    }

    public void getState(final StateCallback stateCallback) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        final boolean listeningEnabled = preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true);
        final boolean recording = (state == STATE_RECORDING);
        final Handler sourceHandler = new Handler();
        // Note that we may not run this for quite a while, if audioReader decides to read a lot of audio!
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                flushAudioRecord();
                final AudioMemory.Stats stats = audioMemory.getStats(FILL_RATE);
                
                int recorded = 0;
                if(wavFileWriter != null) {
                    recorded += wavFileWriter.getTotalSampleBytesWritten();
                    recorded += stats.estimation;
                }
                final float bytesToSeconds = getBytesToSeconds();
                final int finalRecorded = recorded;
                sourceHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        stateCallback.state(listeningEnabled, recording,
                                (stats.overwriting ? stats.total : stats.filled + stats.estimation) * bytesToSeconds,
                                stats.total * bytesToSeconds,
                                finalRecorded * bytesToSeconds);
                    }
                });
            }
        });
    }

    public float getBytesToSeconds() {
        return 1f / FILL_RATE;
    }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() {
            return SaidItService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        if (intent != null && intent.getAction() != null) {
            handleWidgetAction(intent.getAction());
        }
        return START_STICKY;
    }

    // ---- Widget controls -------------------------------------------------

    /** File name used for every automatically named recording, e.g. Echo_2026-11-16_09-27-45.wav */
    static String timestampedFileName(long millis) {
        return "Echo_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(millis)) + ".wav";
    }

    private void handleWidgetAction(String action) {
        Log.d(TAG, "Widget action: " + action);
        if (!ACTION_WIDGET_DELETE.equals(action)) {
            deleteConfirmUntil = 0;
        }
        switch (action) {
            case ACTION_WIDGET_START:
                widgetStart();
                break;
            case ACTION_WIDGET_PAUSE:
                widgetPause();
                break;
            case ACTION_WIDGET_STOP:
                widgetStop();
                break;
            case ACTION_WIDGET_SAVE:
                widgetSave();
                break;
            case ACTION_WIDGET_DELETE:
                widgetDelete();
                break;
        }
        if (state != STATE_READY) {
            // stopRecording() drops the foreground notification; keep the service in the foreground
            // while it is still using the microphone.
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        }
        publishState();
    }

    /** Start: resume if paused, otherwise begin recording to a new file from this moment. */
    void widgetStart() {
        if (pauseRequested) {
            setPaused(false);
            if (state == STATE_RECORDING) {
                showToast(getString(R.string.widget_resumed));
                return;
            }
        }
        if (state == STATE_RECORDING) return;
        if (state == STATE_READY) {
            enableListening();
        }
        startRecording(0);
        showToast(getString(R.string.widget_started));
    }

    /** Pause: keep everything already captured, but ignore the microphone until Start is pressed. */
    void widgetPause() {
        if (state == STATE_READY) {
            showToast(getString(R.string.widget_not_running));
            return;
        }
        if (pauseRequested) return;
        setPaused(true);
        showToast(getString(R.string.widget_paused));
    }

    /** Stop: save what has been captured (no extra step needed), then turn Echo off. */
    void widgetStop() {
        if (state == STATE_READY) {
            setPaused(false);
            showToast(getString(R.string.widget_not_running));
            return;
        }
        saveCurrent();
        // Runs on the audio thread after the save above has finished reading audio.
        pauseRequested = false;
        audioHandler.post(() -> paused = false);
        disableListening();
        stopForeground(true);
        stopSelf();
    }

    /** Save: write what has been captured so far to a file, and keep going. */
    void widgetSave() {
        if (state == STATE_READY) {
            showToast(getString(R.string.nothing_to_save));
            return;
        }
        final boolean wasRecording = state == STATE_RECORDING;
        saveCurrent();
        if (wasRecording) {
            // Continue into a fresh file so the recording carries on.
            startRecording(0);
        }
    }

    /**
     * Delete: throw away the unsaved recording and the memory buffer, then stop recording.
     * Already-saved files in the library are left alone.
     * The first tap only asks for confirmation; a second tap within 3 seconds does the delete.
     */
    void widgetDelete() {
        if (state == STATE_READY) {
            deleteConfirmUntil = 0;
            showToast(getString(R.string.widget_nothing_to_delete));
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now > deleteConfirmUntil) {
            deleteConfirmUntil = now + DELETE_CONFIRM_WINDOW_MS;
            showToast(getString(R.string.widget_tap_again_to_delete));
            return;
        }
        deleteConfirmUntil = 0;

        pauseRequested = false;
        if (state == STATE_RECORDING) {
            state = STATE_LISTENING;
            audioHandler.post(() -> {
                flushAudioRecord();
                closeAndDeleteUnsavedFile();
                audioMemory.clear();
                paused = false;
            });
            final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
            if (!preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
                innerStopListening();
            }
            stopForeground(true);
        } else if (state == STATE_LISTENING) {
            audioHandler.post(() -> {
                if (audioRecord != null) flushAudioRecord();
                audioMemory.clear();
                paused = false;
            });
        }
        showToast(getString(R.string.widget_deleted));
    }

    /** Close the file being recorded and remove it. Must run on the audio thread. */
    private void closeAndDeleteUnsavedFile() {
        if (wavFileWriter != null) {
            try {
                wavFileWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing unsaved recording", e);
            }
            wavFileWriter = null;
        }
        if (wavFile != null) {
            if (wavFile.exists() && !wavFile.delete()) {
                Log.w(TAG, "Could not delete unsaved recording " + wavFile.getAbsolutePath());
            }
            wavFile = null;
        }
    }

    /** Finishes the file being recorded, or, if only the memory buffer is running, saves the whole buffer. */
    private void saveCurrent() {
        final WavFileReceiver receiver = new WidgetFileReceiver();
        if (state == STATE_RECORDING) {
            stopRecording(receiver, "");
        } else if (state == STATE_LISTENING) {
            dumpRecording(ALL_MEMORY_SECONDS, receiver, "");
        }
    }

    private void setPaused(final boolean value) {
        pauseRequested = value;
        // Flush audio up to this moment using the old setting, then switch.
        audioHandler.post(() -> {
            if (audioRecord != null) flushAudioRecord();
            paused = value;
        });
    }

    /** Shows a short confirmation and the usual "Recording saved" notification. */
    class WidgetFileReceiver implements WavFileReceiver {
        @Override
        public void fileReady(final File file, float runtime) {
            mainHandler.post(() -> showToast(getString(R.string.widget_saved, file.getName())));
            new SaidItFragment.NotifyFileReceiver(SaidItService.this).fileReady(file, runtime);
        }
    }

    /** Remembers the current status and redraws any home-screen widgets. */
    void publishState() {
        mainHandler.post(() -> {
            final String status;
            if (state == STATE_READY) status = EchoWidgetProvider.STATUS_STOPPED;
            else if (pauseRequested) status = EchoWidgetProvider.STATUS_PAUSED;
            else if (state == STATE_RECORDING) status = EchoWidgetProvider.STATUS_RECORDING;
            else status = EchoWidgetProvider.STATUS_LISTENING;
            EchoWidgetProvider.setStatus(SaidItService.this, status);
        });
    }

    // Workaround for bug where recent app removal caused service to stop
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT| PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmService = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, SaidItActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, YOUR_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.recording))
                .setSmallIcon(R.drawable.ic_stat_notify_recording)
                .setTicker(getString(R.string.recording))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true); // Ensure notification is ongoing

        // Create the notification channel
        NotificationChannel channel = new NotificationChannel(
                YOUR_NOTIFICATION_CHANNEL_ID,
                "Recording Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);

        return notificationBuilder.build();
    }

}
