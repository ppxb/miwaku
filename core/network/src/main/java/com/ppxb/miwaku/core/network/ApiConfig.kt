package com.ppxb.miwaku.core.network

internal object ApiConfig {
    const val APP_VERSION = "2.1.12"
    const val TOKEN_SECRET = "185Hcomic3PAPP7R"
    const val HOST_CONFIG_SECRET = "diosfjckwpqpdfjkvnqQjsik"
    val DATA_SECRETS = listOf("185Hcomic3PAPP7R", "18comicAPPContent")

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 " +
            "Safari/537.36"

    val DOMAIN_CONFIG_URLS =
        listOf(
            "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt",
        )

    val API_BASES =
        listOf(
            "https://www.cdnhjk.net",
            "https://www.cdngwc.cc",
            "https://www.cdngwc.net",
            "https://www.cdngwc.club",
        )
}

