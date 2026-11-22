package io.github.namanwtf.atom.agent.auth

import com.nimbusds.jwt.SignedJWT
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AppStrings

/** Codex authorization and requests share the same client and workspace context; tokens from other authorization channels must not be reused. */
internal object CodexOAuth {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val CALLBACK_PORT = 1455
    const val ORIGINATOR = "atom"
    const val SCOPE = "openid profile email offline_access"

    fun accountId(accessToken: String): String = try {
        require(accessToken.length in 1..65_536)
        val context = SignedJWT.parse(accessToken).jwtClaimsSet.getJSONObjectClaim("https://api.openai.com/auth")
        (context?.get("chatgpt_account_id") as? String)
            ?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: throw IllegalArgumentException()
    } catch (_: Exception) {
        throw ProviderAuthException(
            "codex_account_missing",
            AppStrings.get(R.string.auth_err_codex_account_missing, "Codex credentials are missing workspace info; please sign in again"),
        )
    }
}
