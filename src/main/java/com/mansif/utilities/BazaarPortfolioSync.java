package com.mansif.utilities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** POSTs MansifUtilities bazaar trades to MansifTracker (Vercel / EC2) for /bazaar-portfolio. */
public final class BazaarPortfolioSync {
    private static final Logger LOGGER = MansifUtilities.LOGGER;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final ConcurrentLinkedQueue<BazaarLog.Transaction> PENDING =
            new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean UPLOAD_IN_FLIGHT = new AtomicBoolean(false);

    private BazaarPortfolioSync() {}

    public static void enqueue(BazaarLog.Transaction tx) {
        PENDING.add(tx);
        scheduleUpload();
    }

    public static void flush() {
        scheduleUpload();
    }

    private static void scheduleUpload() {
        if (!UPLOAD_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        Thread t =
                new Thread(
                        () -> {
                            try {
                                drainUploads();
                            } finally {
                                UPLOAD_IN_FLIGHT.set(false);
                                if (!PENDING.isEmpty()) {
                                    scheduleUpload();
                                }
                            }
                        },
                        "mansifutilities-bazaar-upload");
        t.setDaemon(true);
        t.start();
    }

    private static void drainUploads() {
        FlipBridgeConfig cfg = FlipBridgeConfig.loadAndSync();
        if (!cfg.isUsable()) {
            return;
        }

        List<BazaarLog.Transaction> batch = new ArrayList<>();
        BazaarLog.Transaction tx;
        while ((tx = PENDING.poll()) != null) {
            batch.add(tx);
            if (batch.size() >= 50) {
                postBatch(cfg, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            postBatch(cfg, batch);
        }
    }

    private static void postBatch(FlipBridgeConfig cfg, List<BazaarLog.Transaction> batch) {
        String base = cfg.pollApiBase();
        if (base == null || base.isBlank()) {
            base = cfg.apiBase;
        }
        if (base == null || base.isBlank()) {
            return;
        }
        String url = base.trim().replaceAll("/+$", "") + "/api/bazaar-transactions";
        String json = "{\"transactions\":" + GSON.toJson(batch) + "}";
        for (String tryBase : cfg.pollApiBasesInOrder()) {
            String tryUrl = tryBase.trim().replaceAll("/+$", "") + "/api/bazaar-transactions";
            if (postOnce(tryUrl, cfg.secret, json)) {
                return;
            }
        }
        LOGGER.debug("Bazaar portfolio upload failed for all API bases (last: {})", url);
    }

    private static boolean postOnce(String url, String secret, String json) {
        try {
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Authorization", "Bearer " + secret.trim());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
            int code = conn.getResponseCode();
            if (code == 200) {
                return true;
            }
            LOGGER.debug("Bazaar upload HTTP {} from {}", code, url);
        } catch (Exception e) {
            LOGGER.debug("Bazaar upload failed for {}: {}", url, e.toString());
        }
        return false;
    }
}
