import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.salesup.CrptApi;
import ru.salesup.CrptApi.BodyObject;
import ru.salesup.CrptApi.BodyObject.Product;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CrptApiTest {

    private final String sign = "TEST_SIGN";


    @Test
    @DisplayName("Отправка одного запроса")
    void sendSingleRequest() throws InterruptedException, IOException {
        var crptApi = getCrptApi(10, ChronoUnit.MINUTES);
        crptApi.sendDocument(example, sign);
        waitResult(1, 10, crptApi);
        assertEquals(1, crptApi.getSentRequests().size());
    }

    @Test
    @DisplayName("Отправка 10 запросов")
    void sendTenRequest() throws InterruptedException, IOException {
        var crptApi = getCrptApi(10, ChronoUnit.MINUTES);
        for (int i = 0; i < 10; i++) crptApi.sendDocument(example, sign);
        waitResult(10, 5, crptApi);
        assertEquals(10, crptApi.getSentRequests().size());
    }

    @Test
    @DisplayName("Тест перегрузки запросов")
    void overloadTest() throws InterruptedException, IOException {
        var crptApi = getCrptApi(5, ChronoUnit.MINUTES);
        for (int i = 0; i < 10; i++) crptApi.sendDocument(example, sign);
        waitResult(-1, 5, crptApi);
        assertEquals(5, crptApi.getSentRequests().size());
    }

    @Test
    @DisplayName("Проверка возможности посылать > 1 запроса в секунду")
    void speedTest() throws InterruptedException, IOException {
        var crptApi = getCrptApi(100, ChronoUnit.SECONDS);
        for (int i = 0; i < 20; i++) crptApi.sendDocument(example, sign);

        waitResult(-1, 5, crptApi);
        assertTrue(crptApi.getSentRequests().size() > 10);
    }

    @Test
    @DisplayName("Тест дозагрузки запросов")
    void manyRequests() throws InterruptedException, IOException {
        var crptApi = getCrptApi(1, ChronoUnit.SECONDS);
        for (int i = 0; i < 20; i++) crptApi.sendDocument(example, sign);

        waitResult(-1, 5, crptApi);
        assertTrue(crptApi.getSentRequests().size() <= 5);

        waitResult(-1, 5, crptApi);
        assertTrue(crptApi.getSentRequests().size() <= 10);

        waitResult(20, -1, crptApi);
        assertEquals(20, crptApi.getSentRequests().size());
    }

    private CrptApi getCrptApi(int requestLimit, TemporalUnit unit) throws IOException, InterruptedException {
        var crptApi = new CrptApi(requestLimit, unit);

        var client = mock(HttpClient.class);
        when(client.send(any(), any())).thenReturn(null);
        crptApi.setClient(client);
        return crptApi;
    }

    private void waitResult(int sizeExpected, int secondsWait, CrptApi crptApi) throws InterruptedException {
        int count = 0;
        while (crptApi.getSentRequests().size() != sizeExpected) {
            if (secondsWait == count) return;
            TimeUnit.SECONDS.sleep(1);
            count++;
        }
    }

    private final BodyObject example = new BodyObject(
            new HashMap<>() {{
                put("participantInn", "string");
            }},
            "string",
            "string",
            "LP_INTRODUCE_GOODS",
            true,
            "string",
            "string",
            "string",
            "2020-01-23",
            "string",
            Collections.singletonList(new Product(
                    "string",
                    "2020-01-23",
                    "string",
                    "string",
                    "string",
                    "2020-01-23",
                    "string",
                    "string",
                    "string")),
            "2020-01-23",
            "string");
}
