package com.maxrave.spotify.model.response.spotify

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
@ExperimentalSerializationApi
data class TokenResponse(
    @ProtoNumber(1)
    @SerialName("response_type")
    val responseType: String?,
    @ProtoNumber(2)
    @SerialName("granted_token")
    val grantedToken: GrantedToken?,
) {
    @Serializable
    data class GrantedToken(
        @ProtoNumber(1)
        val token: String?,
        @ProtoNumber(2)
        @SerialName("expires_after_seconds")
        val expiresAfterSeconds: Int?,
        @ProtoNumber(3)
        @SerialName("refresh_after_seconds")
        val refreshAfterSeconds: Int?,
        @ProtoNumber(4)
        val domains: List<Domain>?,
    ) {
        @Serializable
        data class Domain(
            @ProtoNumber(1)
            val domain: String?,
        )
    }
}
/*
{
    "response_type": "RESPONSE_GRANTED_TOKEN_RESPONSE",
    "granted_token": {
        "token": "",
        "expires_after_seconds": 1216800,
        "refresh_after_seconds": 1209600,
        "domains": [
            {
                "domain": "spotify.com"
            },
            {
                "domain": "spotify.net"
            }
        ]
    }
}
 */