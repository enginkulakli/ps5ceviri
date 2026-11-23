import java.lang.reflect.Method;
import java.util.Map;

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
        System.out.println("Google Cloud HTTP error messages: 6 passed");
    }
}
