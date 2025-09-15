package org.example;

import com.alibaba.fastjson2.JSON;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;



public class CrptApi {
    private final int requestLimit;
    private final long intervalNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private long nextRefillTime;
    private int availableTokens;
    private final HttpClient httpClient;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be positive");
        }
        this.requestLimit = requestLimit;
        this.intervalNanos = timeUnit.toNanos(1);
        this.nextRefillTime = System.nanoTime() + intervalNanos;
        this.availableTokens = requestLimit;
        this.httpClient = HttpClient.newHttpClient();
    }

    public void createDocument(Object document, String signature) {
        lock.lock();
        try {
            refillTokens();

            while (availableTokens <= 0) {
                long waitNanos = nextRefillTime - System.nanoTime();
                if (waitNanos <= 0) {
                    refillTokens();
                    break;
                }

                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Request interrupted", e);
                }
                refillTokens();
            }

            availableTokens--;
        } finally {
            lock.unlock();
        }

        try {
            callApi(document, signature);
        } catch (Exception e) {
            throw new RuntimeException("API request failed", e);
        }
    }


    private void refillTokens() {
        long currentTime = System.nanoTime();
        if (currentTime >= nextRefillTime) {
            availableTokens = requestLimit;
            nextRefillTime = currentTime + intervalNanos;
        }
    }


    private void callApi(Object document, String signature) throws Exception {
        String strDoc = Base64.getEncoder().encodeToString(JSON.toJSONBytes(document));

        String requestBody = String.format(
                "{\"document_format\":\"MANUAL\",\"product_document\":\"%s\",\"signature\":\"%s\",\"type\":\"LP_INTRODUCE_GOODS\"}",
                strDoc, signature
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() >= 400) {
            throw new RuntimeException("API request failed with status: " + response.statusCode());
        }
}}