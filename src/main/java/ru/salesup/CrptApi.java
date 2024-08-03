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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static java.net.http.HttpRequest.BodyPublishers.ofString;

@Slf4j
public class CrptApi {

    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private final BlockingQueue<RequestTask> queue = new LinkedBlockingQueue<>(); // В реальном приложении это поедет в Кафку

    @Getter
    private final Set<SentRequestData> sentRequests = Collections.synchronizedSet(new HashSet<>()); // Конечно, это все в БД
    @Setter
    private String url = "https://ismp.crpt.ru/api/v3/lk/documents/create"; // Инжектится из конфигураций/окружения
    @Setter
    private HttpClient client = HttpClient.newHttpClient(); // Нужны настройки авторизации/аутентификации

    /**
     * @param requestLimit Допустимое количество запросов в одну единицу времени (temporalUnit). Должно быть >= 0
     * @param temporalUnit Единица времени
     */
    public CrptApi(Integer requestLimit, ChronoUnit temporalUnit) {
        Objects.requireNonNull(temporalUnit);
        if (Objects.requireNonNull(requestLimit) < 1)
            throw new IllegalArgumentException(String.format(
                    "Невалидное значение лимита запросов (%s). Должно быть положительное число",
                    requestLimit));
        new Thread(() -> {
            while (true) {
                try {
                    var timeBefore = System.currentTimeMillis();
                    for (int i = 0; i < requestLimit; i++) {
                        Optional.ofNullable(queue.poll(1, TimeUnit.of(temporalUnit)))
                                .ifPresent(threadPool::submit);
                    }
                    var millisToSleep = temporalUnit.getDuration().toMillis() - (System.currentTimeMillis() - timeBefore);
                    if (millisToSleep > 0) TimeUnit.MILLISECONDS.sleep(millisToSleep);
                } catch (InterruptedException e) {
                    log.error("Выполнение приложения прервано", e);
                    throw new RuntimeException(e);
                }
            }
        }).start();
    }

    public boolean sendDocument(BodyObject object, String sign) {
        Objects.requireNonNull(object);
        Objects.requireNonNull(sign);

        var result = queue.offer(new RequestTask(object, sign));
        if (result)
            log.info("Получен запрос отправки документа object={}, sign={}", object, sign);
        else
            log.warn("Невозможно принять документ в обработку queueSize={}, object={}, sign={}", queue.size(), object, sign);
        return result;
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
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url + "?sign=" + sign))
                        .header("Content-type", "application/json")
                        .POST(ofString(new ObjectMapper().writeValueAsString(object)))
                        .build();
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                requestResult = RequestResult.SUCCESS;
                log.info("Запрос на создание документа отправлен успешно. Object={}", object.toString());
            } catch (JsonProcessingException e) {
                log.warn("Ошибка сериализации запроса", e);
            } catch (InterruptedException | IOException e) {
                log.warn("Ошибка отправки запроса в ЧЗ", e);
            } catch (Exception e) {
                log.warn("Ошибка выполнения запроса в ЧЗ", e);
            } finally {
                sentRequests.add(new SentRequestData(LocalDateTime.now(), object, requestResult, response));
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