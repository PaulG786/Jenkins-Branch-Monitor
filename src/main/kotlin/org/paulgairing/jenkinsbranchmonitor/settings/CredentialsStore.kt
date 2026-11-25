package org.paulgairing.jenkinsbranchmonitor.settings

import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** API tokens live in the IDE PasswordSafe, never in the settings XML. */
object CredentialsStore {

    // Service name kept from the original plugin name so stored tokens survive the rename.
    private fun attributes(serverUrl: String) =
        CredentialAttributesFactory.create(generateServiceName("Jenkins Branch Monitor", serverUrl))

    fun getToken(serverUrl: String): String? =
        PasswordSafe.instance.getPassword(attributes(serverUrl))

    fun setToken(serverUrl: String, token: String?) {
        PasswordSafe.instance.setPassword(attributes(serverUrl), token?.takeIf { it.isNotBlank() })
    }
}
