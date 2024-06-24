package com.thesol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.*;

public class CrptApi {

    private static final String API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Semaphore requestSemaphore;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final int requestLimit;

    public CrptApi(int requestLimit, long period, TimeUnit timeUnit) {
        this.requestLimit = requestLimit;
        this.requestSemaphore = new Semaphore(requestLimit);
        this.httpClient = HttpClient.newHttpClient();
        this.scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::resetSemaphore, period, period, timeUnit);
    }

    private void resetSemaphore() {
        requestSemaphore.drainPermits();
        requestSemaphore.release(requestLimit);
    }

    public CompletableFuture<HttpResponse<String>> createDocument(String documentJson, String signature) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requestSemaphore.acquire();
                return sendPostRequest(documentJson, signature);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                requestSemaphore.release();
            }
        }, scheduler);
    }

    private HttpResponse<String> sendPostRequest(String documentJson, String signature) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("document", documentJson);
            requestBody.put("signature", signature);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}
