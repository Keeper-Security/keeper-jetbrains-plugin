package keepersecurity.http;

import com.intellij.httpClient.http.request.dynamicVariables.HttpClientDynamicVariableValue;
import com.intellij.openapi.diagnostic.Logger;
import kotlin.jvm.functions.Function2;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Kotlin cannot call the JVM overload {@code FunctionValue(Parameter[], Function2)} without resolving
 * the private Kotlin constructor; this factory targets the public bytecode constructor.
 */
public final class KeeperHttpClientKeeperFunctionValue {
    private KeeperHttpClientKeeperFunctionValue() {
    }

    @NotNull
    public static HttpClientDynamicVariableValue create(@NotNull Logger logger) {
        return new HttpClientDynamicVariableValue.FunctionValue(
                new HttpClientDynamicVariableValue.Parameter[]{
                        new HttpClientDynamicVariableValue.Parameter(
                                "recordUid",
                                HttpClientDynamicVariableValue.ParameterType.STRING),
                        new HttpClientDynamicVariableValue.Parameter(
                                "field",
                                HttpClientDynamicVariableValue.ParameterType.STRING),
                },
                new Function2<
                        HttpClientDynamicVariableValue.FunctionValue.FunctionContext,
                        List<? extends HttpClientDynamicVariableValue.ParameterValue>,
                        String>() {
                    @Override
                    public String invoke(
                            HttpClientDynamicVariableValue.FunctionValue.FunctionContext functionContext,
                            List<? extends HttpClientDynamicVariableValue.ParameterValue> args) {
                        return KeeperHttpSecretResolver.INSTANCE.resolveRecordField(
                                ((HttpClientDynamicVariableValue.ParameterValue.Str) args.get(0)).getValue(),
                                ((HttpClientDynamicVariableValue.ParameterValue.Str) args.get(1)).getValue(),
                                logger);
                    }
                });
    }
}
