/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.marcphilipp.gradle.nexus.internal

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import okhttp3.Credentials
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

import java.io.IOException
import java.io.UncheckedIOException
import java.net.URI
import java.util.concurrent.TimeUnit

class NexusClient(private val baseUrl: URI, username: String?, password: String?) {
    private val api: NexusApi

    init {
        val httpClientBuilder = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.MINUTES)
                .readTimeout(1, TimeUnit.MINUTES)
                .writeTimeout(1, TimeUnit.MINUTES)
        if (username != null || password != null) {
            httpClientBuilder
                    .addInterceptor({ chain ->
                        chain.proceed(chain.request().newBuilder()
                                .header("Authorization", Credentials.basic(username ?: "", password ?: ""))
                                .build())
                    })
        }
        val gson = GsonBuilder()
                .registerTypeAdapterFactory(WrappingTypeAdapterFactory())
                .create()
        val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl.toString())
                .client(httpClientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
        api = retrofit.create(NexusApi::class.java)
    }

    fun findStagingProfileId(packageGroup: String): String? {
        try {
            val response = api.stagingProfiles.execute()
            if (!response.isSuccessful) {
                throw failure("load staging profiles", response)
            }
            return response.body()
                    ?.filter { profile -> profile.name == packageGroup }
                    ?.map { it.id }
                    ?.firstOrNull()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun createStagingRepository(stagingProfileId: String, description: String): String {
        try {
            val response = api.startStagingRepo(stagingProfileId, Description(description)).execute()
            if (!response.isSuccessful) {
                throw failure("create staging repository", response)
            }
            return response.body()?.stagedRepositoryId ?: throw RuntimeException("No response body")
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun getStagingRepositoryUri(stagingRepositoryId: String): URI =
            URI.create("${baseUrl.toString().removeSuffix("/")}/staging/deployByRepositoryId/$stagingRepositoryId")

    private fun failure(action: String, response: Response<*>): RuntimeException {
        var message = "Failed to " + action + ", server responded with status code " + response.code()
        val errorBody = response.errorBody()
        if (errorBody != null && errorBody.contentLength() > 0) {
            try {
                message += ", body: " + errorBody.string()
            } catch (e: IOException) {
                throw UncheckedIOException("Failed to read body of error response", e)
            }
        }
        return RuntimeException(message)
    }

    private interface NexusApi {

        @get:Headers("Accept: application/json")
        @get:GET("staging/profiles")
        val stagingProfiles: Call<List<StagingProfile>>

        @Headers("Content-Type: application/json")
        @POST("staging/profiles/{stagingProfileId}/start")
        fun startStagingRepo(@Path("stagingProfileId") stagingProfileId: String, @Body description: Description): Call<StagingRepository>
    }

    data class StagingProfile(var id: String, var name: String)

    data class Description(val description: String)

    data class StagingRepository(var stagedRepositoryId: String)

    private class WrappingTypeAdapterFactory : TypeAdapterFactory {

        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
            val delegate = gson.getDelegateAdapter(this, type)
            val elementAdapter = gson.getAdapter(JsonElement::class.java)
            return object : TypeAdapter<T>() {
                @Throws(IOException::class)
                override fun write(writer: JsonWriter, value: T) {
                    if (value !is Description) {
                        delegate.write(writer, value)
                        return
                    }
                    // Only Description is to be wrapped with extra {data:...} for now
                    // Otherwise requests are sent as {data:{description:{data:"message"}}}
                    // which is not right.
                    val jsonObject = JsonObject()
                    jsonObject.add("data", delegate.toJsonTree(value))
                    elementAdapter.write(writer, jsonObject)
                }

                @Throws(IOException::class)
                override fun read(reader: JsonReader): T {
                    var jsonElement = elementAdapter.read(reader)
                    if (jsonElement.isJsonObject) {
                        val jsonObject = jsonElement.asJsonObject
                        if (jsonObject.has("data")) {
                            jsonElement = jsonObject.get("data")
                        }
                    }
                    return delegate.fromJsonTree(jsonElement)
                }
            }.nullSafe()
        }
    }
}
