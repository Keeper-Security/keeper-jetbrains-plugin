package keepersecurity.http

import com.intellij.httpClient.http.request.dynamicVariables.HttpClientDynamicVariables
import com.intellij.httpClient.http.request.dynamicVariables.HttpClientDynamicVariablesCollection
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Registers JetBrains HTTP Client dynamic variables backed by Keeper (persistent shell + `get` JSON).
 *
 * Use in `.http` files, e.g. `GET https://example.com?q={{ $keeper("RECORD_UID", "password") }}`
 * (same field paths as `.env` references: `keeper://UID/field/...`).
 */
class KeeperHttpClientDynamicVariablesProvider : HttpClientDynamicVariables.DynamicVariablesProvider {

    private val logger = thisLogger()

    override fun HttpClientDynamicVariablesCollection.VariableBuilder.provideDynamicVariables() {
        KEEPER_FUNCTION_NAME.computedAs(
            KeeperHttpClientKeeperFunctionValue.create(logger),
        )
    }

    companion object {
        /** Root name for `{{ $keeper("uid", "field") }}`. */
        const val KEEPER_FUNCTION_NAME: String = "keeper"
    }
}
