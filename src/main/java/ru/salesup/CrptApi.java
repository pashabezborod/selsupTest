package ru.salesup;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static java.net.http.HttpRequest.BodyPublishers.ofString;

@Slf4j
@RequiredArgsConstructor
public class CrptApi {

    private final Integer requestLimit;
    private final TemporalUnit temporalUnit;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    @Getter
    private final Set<SentRequestData> sentRequests = Collections.synchronizedSet(new HashSet<>()); // Конечно, это все в БД
    @Setter
    private String url = "https://ismp.crpt.ru/api/v3/lk/documents/create"; // Инжектится из конфигураций/окружения
    @Setter
    private HttpClient client = HttpClient.newHttpClient(); // Нужны настройки авторизации/аутентификации

    public void sendDocument(BodyObject object, String sign) {
        log.info("Получен запрос отправки документа object={}, sign={}", object, sign);
        threadPool.submit(new RequestTask(object, sign));
    }

    private boolean checkCanSendRequest() {
        var fromTime = LocalDateTime.now().minus(1, temporalUnit);
        return sentRequests.stream()
                .filter(it -> it.dateTime().isAfter(fromTime))
                .count() < requestLimit;
    }

    @RequiredArgsConstructor
    private class RequestTask implements Runnable {

        private final BodyObject object;
        private final String sign;

        @Override
        public void run() {
            var requestResult = RequestResult.FAILED;
            Object response = null;
            try {
                lock.lock();
                while (!checkCanSendRequest()) TimeUnit.MILLISECONDS.sleep(100);
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url + "?sign=" + sign))
                        .header("Content-type", "application/json")
                        .POST(ofString(new ObjectMapper().writeValueAsString(object)))
                        .build();
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                log.info("Запрос на создание документа отправлен успешно. Object={}", object.toString());
            } catch (JsonProcessingException e) {
                log.warn("Ошибка сериализации запроса", e);
            } catch (InterruptedException | IOException e) {
                log.warn("Ошибка отправки запроса в ЧЗ", e);
            } finally {
                sentRequests.add(new SentRequestData(LocalDateTime.now(), object, requestResult, response));
                lock.unlock();
            }
        }
    }

    @JsonSerialize
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public record BodyObject(
            Map<String, String> description,
            String doc_id,
            String doc_status,
            String doc_type,
            Boolean importRequest,
            String owner_inn,
            String participant_inn,
            String producer_inn,
            String production_date,
            String production_type,
            Collection<Product> products,
            String req_date,
            String req_number) {
        @JsonSerialize
        @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
        public record Product(
                String certificate_document,
                String certificate_document_date,
                String certificate_document_number,
                String owner_inn,
                String producer_inn,
                String production_date,
                String tnved_code,
                String uit_code,
                String uitu_code) {
        }
    }

    public record SentRequestData(
            LocalDateTime dateTime,
            BodyObject object,
            RequestResult requestResult,
            Object response) {
    }

    public enum RequestResult {
        SUCCESS,
        FAILED
    }
}