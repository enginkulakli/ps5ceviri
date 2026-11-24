import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;

class CloudErrorChecks {
    public static void main(String[] args) throws Exception {
        Class<?> provider = Class.forName("expo.modules.ps5translator.GoogleCloudTranslationKt");
        Method error = provider.getDeclaredMethod("cloudHttpError", int.class);
        for (var test : Map.of(400, "geçersiz", 401, "geçersiz", 403, "reddedildi",
                              429, "kota", 503, "geçici", 418, "HTTP 418").entrySet()) {
            String message = (String) error.invoke(null, test.getKey());
            if (!message.contains(test.getValue())) {
                throw new AssertionError("Unexpected HTTP " + test.getKey() + ": " + message);
            }
        }
        var request = provider.getDeclaredMethod("cloudRequest", List.class);
        byte[] body = (byte[]) request.invoke(null, List.of("Gate 2", "Mandy's rent"));
        var params = new JSONObject(new String(body, StandardCharsets.UTF_8));
        if (!"en".equals(params.getString("source")) || !"tr".equals(params.getString("target")) ||
            !"text".equals(params.getString("format")) || params.has("key") ||
            !"Mandy's rent".equals(params.getJSONArray("q").getString(1))) {
            throw new AssertionError("Unexpected Basic v2 request body");
        }
        var response = provider.getDeclaredMethod("cloudResponse", String.class, int.class);
        String sample = """
            {"data":{"translations":[{"translatedText":"Geçit 2"},
              {"translatedText":"Mandy&#39;nin kirası"}]}}
            """;
        if (!List.of("Geçit 2", "Mandy'nin kirası").equals(response.invoke(null, sample, 2))) {
            throw new AssertionError("Unexpected Basic v2 translated text");
        }
        try {
            response.invoke(null, sample, 3);
            throw new AssertionError("Partial Basic v2 response was accepted");
        } catch (InvocationTargetException expected) {
            if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
        }
        System.out.println("Google Cloud Basic v2: 6 HTTP errors and 3 request/response checks passed");
    }
}
