// Minimal API shapes of OkHttp 4 and kotlinx.coroutines, used ONLY by scripts/android_typecheck.sh so the
// Android sources can be type-checked against android.jar on a machine without Gradle/Maven access.
package okhttp3

import java.io.InputStream
import java.util.concurrent.TimeUnit

class OkHttpClient private constructor() {
    class Builder {
        fun connectTimeout(t: Long, u: TimeUnit): Builder = this
        fun readTimeout(t: Long, u: TimeUnit): Builder = this
        fun build(): OkHttpClient = OkHttpClient()
    }
    fun newCall(r: Request): Call = throw NotImplementedError()
}

class Request private constructor() {
    class Builder {
        fun url(u: String): Builder = this
        fun header(n: String, v: String): Builder = this
        fun build(): Request = Request()
    }
}

interface Call { fun execute(): Response }

class Response : java.io.Closeable {
    val isSuccessful: Boolean = true
    val code: Int = 200
    val body: ResponseBody? = null
    override fun close() {}
}

class ResponseBody {
    fun string(): String = ""
    fun contentLength(): Long = 0
    fun byteStream(): InputStream = throw NotImplementedError()
}
