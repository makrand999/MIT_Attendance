package com.mit.attendance.data.api

import com.mit.attendance.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface AlertApiService {

    // --- Read operations (CDN - No Auth) ---

    @GET
    @Headers("Cache-Control: no-cache")
    suspend fun getRawFile(@Url url: String): Response<ResponseBody>

    // --- Write operations (GitHub API - Auth required) ---

    @GET("gists/{gist_id}")
    suspend fun getGist(
        @Header("Authorization") token: String?,
        @Path("gist_id") gistId: String
    ): Response<GistResponse>

    @PATCH("gists/{gist_id}")
    suspend fun updateGist(
        @Header("Authorization") token: String?,
        @Path("gist_id") gistId: String,
        @Body body: GistRequest
    ): Response<GistResponse>

    @POST("gists")
    suspend fun createGist(
        @Header("Authorization") token: String?,
        @Body body: GistRequest
    ): Response<GistResponse>
}
