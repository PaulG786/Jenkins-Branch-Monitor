package org.paulgairing.jenkinsbranchmonitor.settings;

import com.intellij.credentialStore.CredentialAttributes;

/**
 * The plain {@code CredentialAttributes(String)} constructor is a {@code @JvmOverloads} overload that Kotlin cannot
 * select; Kotlin would bind to the default-arguments constructor, which is deprecated in newer platform versions.
 */
final class CredentialAttributesFactory {

    private CredentialAttributesFactory() {
    }

    static CredentialAttributes create(String serviceName) {
        return new CredentialAttributes(serviceName);
    }
}
