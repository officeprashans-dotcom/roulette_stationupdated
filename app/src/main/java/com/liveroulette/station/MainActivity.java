package com.liveroulette.station;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Roulette station kiosk: full-screen WebView, keeps the screen on, ignores back/gestures,
 * and FINDS THE SERVER BY ITSELF if the main PC's IP address changes.
 * Tap the top-left corner 5 times to open the setup screen (PIN protected).
 */
public class MainActivity extends Activity {
    static final int PORT = 8765;

    private SharedPreferences sp;
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean offline = false, scanning = false, dialogOpen = false, watching = false;
    private int failCount = 0, probeFails = 0, taps = 0;
    private long firstTap = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#05204a"));
        web = new WebView(this);
        setupWebView();
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View hot = new View(this);   // invisible 5-tap area for the setup screen
        hot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                secretTap();
            }
        });
        int px = dp(110);
        root.addView(hot, new FrameLayout.LayoutParams(px, px, Gravity.TOP | Gravity.START));
        setContentView(root);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int flags) {
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                hideBars();
                            }
                        }, 400);
                    }
                });
        if (sp.getInt("station", 0) == 0) showSetup(true);
        else begin();
    }

    // ---------------------------------------------------------------- kiosk behaviour
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
    }

    @Override
    public void onBackPressed() {
        // kiosk: back button does nothing
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " RouletteApp");
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return true;
            }
        });
        web.setBackgroundColor(Color.parseColor("#05204a"));
        web.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                goOffline();   // only called for the main page on every Android version
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!offline && url != null && url.startsWith("http")) {
                    ui.removeCallbacks(retry);
                    failCount = 0;
                }
            }
        });
    }

    // ---------------------------------------------------------------- loading / recovery
    private String host() {
        return sp.getString("host", "");
    }

    private int station() {
        return sp.getInt("station", 1);
    }

    private String base(String h) {
        return "http://" + (h.contains(":") ? h : h + ":" + PORT);
    }

    private void begin() {
        if (sp.getBoolean("lock", false)) {
            try {
                startLockTask();
            } catch (Exception ignored) {
            }
        } else {
            try {
                stopLockTask();
            } catch (Exception ignored) {
            }
        }
        failCount = 0;
        offline = false;
        if (host().isEmpty()) {
            goOffline();
            discover();
        } else {
            web.loadUrl(base(host()) + "/station/" + station());
        }
        if (!watching) {
            watching = true;
            ui.postDelayed(watchdog, 8000);
        }
    }

    private void goOffline() {
        if (offline) return;
        offline = true;
        failCount++;
        String h = host().isEmpty() ? "searching for server" : host();
        String html = "<body style='margin:0;background:#05204a;color:#fff;font-family:sans-serif;display:flex;"
                + "align-items:center;justify-content:center;height:100vh;text-align:center'><div>"
                + "<div style='font-size:46px;font-weight:700'>Connecting to server...</div>"
                + "<div style='margin-top:18px;font-size:22px;color:#9fc0f5'>Station " + station() + " - " + h
                + " - attempt " + failCount + "</div>"
                + "<div style='margin-top:30px;font-size:16px;color:#6f8fc4'>Tap the top-left corner 5 times for setup</div>"
                + "</div></body>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, 3000);
    }

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (dialogOpen) {
                ui.postDelayed(this, 3000);
                return;
            }
            offline = false;
            if (host().isEmpty() || failCount % 3 == 0) discover();   // every ~9 s look for a moved server
            if (host().isEmpty()) goOffline();
            else web.loadUrl(base(host()) + "/station/" + station());
        }
    };

    /** Every 8 s: is the server still answering at the saved address? (catches an IP change mid-session) */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = !host().isEmpty() && probe(host());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (ok) {
                                probeFails = 0;
                            } else if (++probeFails >= 3 && !offline && !dialogOpen) {
                                probeFails = 0;
                                goOffline();
                            }
                        }
                    });
                }
            }).start();
            ui.postDelayed(this, 8000);
        }
    };

    private boolean probe(String h) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base(h) + "/api/v1/ping").openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(800);
            if (c.getResponseCode() != 200) return false;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line = r.readLine();
            r.close();
            return line != null && line.contains("roulette-server");
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> subnetHosts() {
        List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address) || !ia.getAddress().isSiteLocalAddress()) continue;
                    byte[] b = ia.getAddress().getAddress();
                    for (int i = 1; i < 255; i++) {
                        out.add((b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + "." + i);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Scan the local /24 network for the roulette server and remember where it is. */
    private void discover() {
        if (scanning) return;
        scanning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AtomicReference<String> found = new AtomicReference<String>();
                List<String> candidates = new ArrayList<String>();
                String known = host();
                if (!known.isEmpty() && !known.contains(":")) candidates.add(known);
                candidates.addAll(subnetHosts());
                ExecutorService pool = Executors.newFixedThreadPool(48);
                for (final String ip : candidates) {
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (found.get() == null && probe(ip)) found.compareAndSet(null, ip);
                        }
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(45, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                final String ip = found.get();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (ip != null && !dialogOpen) {
                            sp.edit().putString("host", ip).apply();
                            ui.removeCallbacks(retry);
                            offline = false;
                            web.loadUrl(base(ip) + "/station/" + station());
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- setup screen
    private void secretTap() {
        long now = System.currentTimeMillis();
        if (now - firstTap > 4000) {
            firstTap = now;
            taps = 0;
        }
        if (++taps >= 5) {
            taps = 0;
            askPin();
        }
    }

    private EditText field(String hint, int type, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    private void askPin() {
        if (dialogOpen) return;
        dialogOpen = true;
        final EditText pin = field("Setup PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, "");
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Enter setup PIN").setView(pin)
                .setPositiveButton("OK", null).setNegativeButton("Cancel", null).create();
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface x) {
                dialogOpen = false;
            }
        });
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pin.getText().toString().equals(sp.getString("pin", "1234"))) {
                    d.dismiss();
                    showSetup(false);
                } else {
                    Toast.makeText(MainActivity.this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void showSetup(final boolean first) {
        dialogOpen = true;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(10));
        int st0 = sp.getInt("station", 0);
        final EditText st = field("Station number (1-8)", InputType.TYPE_CLASS_NUMBER, st0 == 0 ? "" : String.valueOf(st0));
        final EditText host = field("Server address (empty = find automatically)", InputType.TYPE_CLASS_TEXT, host());
        final EditText pin = field(first ? "Choose a setup PIN (4+ digits)" : "New setup PIN (empty = keep)",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, "");
        final CheckBox lock = new CheckBox(this);
        lock.setText("Lock screen (screen pinning)");
        lock.setChecked(sp.getBoolean("lock", false));
        Button perm = new Button(this);
        perm.setText("Allow auto-start (Display over other apps)");
        perm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
            }
        });
        box.addView(st);
        box.addView(host);
        box.addView(pin);
        box.addView(lock);
        box.addView(perm);
        final AlertDialog d = new AlertDialog.Builder(this).setTitle("Station setup").setView(box)
                .setCancelable(!first).setPositiveButton("Save", null)
                .setNegativeButton(first ? null : "Cancel", null).create();
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface x) {
                dialogOpen = false;
            }
        });
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int n;
                try {
                    n = Integer.parseInt(st.getText().toString().trim());
                } catch (Exception e) {
                    n = 0;
                }
                if (n < 1 || n > 8) {
                    st.setError("1 to 8");
                    return;
                }
                String p = pin.getText().toString().trim();
                if (first && p.length() < 4) {
                    pin.setError("at least 4 digits");
                    return;
                }
                SharedPreferences.Editor ed = sp.edit().putInt("station", n)
                        .putString("host", host.getText().toString().trim())
                        .putBoolean("lock", lock.isChecked());
                if (!p.isEmpty()) ed.putString("pin", p);
                ed.apply();
                d.dismiss();
                begin();
            }
        });
    }
}
