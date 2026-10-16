package com.desktopmini.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Nhận dạng giọng nói tiếng Việt cho nút AI (học theo VoiceInput trong app Nokia).
 * Có mạng: nhận dạng trực tuyến; mất mạng / lỗi mạng giữa chừng: tự chuyển sang gói giọng nói ngoại tuyến.
 * Đẩy sự kiện sang giao diện qua window.aiVoice({t:'ready'|'partial'|'level'|'result'|'error', ...}).
 * Mọi hàm gọi trên luồng chính.
 */
class VoiceAi {
    static final long SILENCE_MS = 2000L;      // im lặng đủ lâu thì chốt luôn
    static final long START_WAIT_MS = 3000L;   // bấm nút xong, quá chừng này chưa ai nói thì dừng nghe
    static final int ERR_NO_OFFLINE = 9001;    // không có mạng và chưa tải gói tiếng Việt ngoại tuyến

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private boolean active;

    private final Runnable silenceStop = new Runnable() { public void run() { stop(); } };
    private final Runnable startTimeout = new Runnable() { public void run() {
        cancel();
        emitError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
    } };

    VoiceAi(Context c) { ctx = c; }

    boolean available() {
        try { return SpeechRecognizer.isRecognitionAvailable(ctx); } catch (Throwable t) { return false; }
    }

    boolean isActive() { return active; }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) { return true; }
    }

    private void armSilence() { handler.removeCallbacks(silenceStop); handler.postDelayed(silenceStop, SILENCE_MS); }
    private void disarm() { handler.removeCallbacks(silenceStop); handler.removeCallbacks(startTimeout); }

    private void emit(String json) { MainActivity.push("aiVoice", json); }
    private void emitError(int code) {
        active = false;
        emit("{\"t\":\"error\",\"code\":" + code + "}");
    }

    void start() {
        cancel();
        active = true;
        startRec(!online());
    }

    private void startRec(final boolean offline) {
        SpeechRecognizer r = null;
        try { r = SpeechRecognizer.createSpeechRecognizer(ctx); } catch (Throwable t) { }
        if (r == null) { emitError(SpeechRecognizer.ERROR_CLIENT); return; }
        rec = r;
        r.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                emit("{\"t\":\"ready\"}");
                handler.removeCallbacks(startTimeout); handler.postDelayed(startTimeout, START_WAIT_MS);
            }
            @Override public void onBeginningOfSpeech() { handler.removeCallbacks(startTimeout); armSilence(); }
            @Override public void onRmsChanged(float rmsdB) { emit("{\"t\":\"level\",\"v\":" + rmsdB + "}"); }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int error) {
                disarm();
                boolean netErr = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == SpeechRecognizer.ERROR_SERVER;
                if (!offline && netErr) {
                    // Mạng chập chờn / máy chủ lỗi -> thử lại một lần bằng gói ngoại tuyến
                    handler.post(new Runnable() { public void run() { cancelRec(); if (active) startRec(true); } });
                } else if (offline && (netErr || error == 12 || error == 13)) {
                    cancelRec(); emitError(ERR_NO_OFFLINE);
                } else { cancelRec(); emitError(error); }
            }
            @Override public void onResults(Bundle results) {
                disarm();
                ArrayList<String> l = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                JSONArray arr = new JSONArray();
                if (l != null) for (String s : l) arr.put(s);
                active = false;
                cancelRec();
                try { emit(new JSONObject().put("t", "result").put("list", arr).toString()); } catch (Exception e) { }
            }
            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> l = partial == null ? null : partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) {
                    try { emit(new JSONObject().put("t", "partial").put("text", l.get(0)).toString()); } catch (Exception e) { }
                    handler.removeCallbacks(startTimeout);
                    armSilence();      // còn đang nói -> đếm lại 2 giây
                }
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        if (offline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        try { r.startListening(i); } catch (Throwable t) { cancelRec(); emitError(SpeechRecognizer.ERROR_CLIENT); }
    }

    /** Nói xong sớm: nhận dạng luôn phần đã nói. */
    void stop() { try { if (rec != null) rec.stopListening(); } catch (Throwable t) { } }

    private void cancelRec() {
        SpeechRecognizer r = rec; rec = null;
        if (r == null) return;
        try { r.setRecognitionListener(null); } catch (Throwable t) { }
        try { r.cancel(); } catch (Throwable t) { }
        try { r.destroy(); } catch (Throwable t) { }
    }

    /** Hủy hẳn, không trả kết quả. */
    void cancel() {
        disarm();
        active = false;
        cancelRec();
    }
}
