package com.whispertflite;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.utils.WaveUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ContinuousTranscriptionActivity extends AppCompatActivity {
    private static final String TAG = "ContinuousTranscription";

    private static final String DEFAULT_MODEL = "whisper-tiny.tflite";
    private static final String ENGLISH_ONLY_VOCAB = "filters_vocab_en.bin";
    private static final String MULTILINGUAL_VOCAB = "filters_vocab_multilingual.bin";

    private RecyclerView rvTranscription;
    private TranscriptionAdapter adapter;
    private ExtendedFloatingActionButton fabRecord;
    private LinearProgressIndicator progressIndicator;
    private View emptyState;

    private Recorder mRecorder;
    private Whisper mWhisper;
    private File sdcardDataFolder;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_continuous);

        rvTranscription = findViewById(R.id.rvTranscription);
        fabRecord = findViewById(R.id.fabRecord);
        progressIndicator = findViewById(R.id.processing_indicator);
        emptyState = findViewById(R.id.empty_state);

        adapter = new TranscriptionAdapter();
        rvTranscription.setLayoutManager(new LinearLayoutManager(this));
        rvTranscription.setAdapter(adapter);

        sdcardDataFolder = getExternalFilesDir(null);
        copyAssetsToSdcard();

        initWhisper();
        initRecorder();

        fabRecord.setOnClickListener(v -> {
            if (mRecorder != null && mRecorder.isInProgress()) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        checkRecordPermission();
    }

    private void initWhisper() {
        File modelFile = new File(sdcardDataFolder, DEFAULT_MODEL);
        boolean isMultilingual = !DEFAULT_MODEL.endsWith(".en.tflite");
        String vocabFileName = isMultilingual ? MULTILINGUAL_VOCAB : ENGLISH_ONLY_VOCAB;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingual);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Whisper: " + message);
                handler.post(() -> {
                    if (Whisper.MSG_PROCESSING.equals(message)) {
                        progressIndicator.setVisibility(View.VISIBLE);
                    } else if (Whisper.MSG_PROCESSING_DONE.equals(message)) {
                        progressIndicator.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onResultReceived(String result) {
                if (result != null && !result.trim().isEmpty()) {
                    handler.post(() -> {
                        adapter.addTranscription(result);
                        rvTranscription.smoothScrollToPosition(adapter.getItemCount() - 1);
                        emptyState.setVisibility(View.GONE);
                    });
                }
            }
        });
    }

    private void initRecorder() {
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Recorder: " + message);
                handler.post(() -> {
                    if (Recorder.MSG_RECORDING.equals(message)) {
                        fabRecord.setText("Stop");
                        fabRecord.setIconResource(android.R.drawable.ic_media_pause);
                    } else if (Recorder.MSG_RECORDING_DONE.equals(message)) {
                        fabRecord.setText("Record");
                        fabRecord.setIconResource(android.R.drawable.ic_btn_speak_now);
                    }
                });
            }

            @Override
            public void onDataReceived(float[] samples) {
                if (mWhisper != null) {
                    mWhisper.writeBuffer(samples);
                }
            }
        });
    }

    private void startRecording() {
        File waveFile = new File(sdcardDataFolder, WaveUtil.RECORDING_FILE);
        mRecorder.setFilePath(waveFile.getAbsolutePath());
        mRecorder.start();
    }

    private void stopRecording() {
        mRecorder.stop();
    }

    private void copyAssetsToSdcard() {
        AssetManager assetManager = getAssets();
        String[] extensions = {"tflite", "bin"};
        try {
            String[] assetFiles = assetManager.list("");
            if (assetFiles == null) return;
            for (String assetFileName : assetFiles) {
                for (String extension : extensions) {
                    if (assetFileName.endsWith("." + extension)) {
                        File outFile = new File(sdcardDataFolder, assetFileName);
                        if (!outFile.exists()) {
                            try (InputStream in = assetManager.open(assetFileName);
                                 OutputStream out = new FileOutputStream(outFile)) {
                                byte[] buffer = new byte[1024];
                                int read;
                                while ((read = in.read(buffer)) != -1) {
                                    out.write(buffer, 0, read);
                                }
                            }
                        }
                        break;
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Asset copy failed", e);
        }
    }

    private void checkRecordPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0);
        }
    }

    private static class TranscriptionAdapter extends RecyclerView.Adapter<TranscriptionAdapter.ViewHolder> {
        private final List<TranscriptionItem> items = new ArrayList<>();

        static class TranscriptionItem {
            String text;
            String timestamp;

            TranscriptionItem(String text) {
                this.text = text;
                this.timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transcription, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TranscriptionItem item = items.get(position);
            holder.tvContent.setText(item.text);
            holder.tvTimestamp.setText(item.timestamp);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        void addTranscription(String text) {
            items.add(new TranscriptionItem(text));
            notifyItemInserted(items.size() - 1);
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvContent, tvTimestamp;

            ViewHolder(View itemView) {
                super(itemView);
                tvContent = itemView.findViewById(R.id.tvContent);
                tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            }
        }
    }
}
