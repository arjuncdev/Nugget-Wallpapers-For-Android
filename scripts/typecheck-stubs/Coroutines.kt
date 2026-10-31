package kotlinx.coroutines

open class CoroutineDispatcher
object Dispatchers { val IO: CoroutineDispatcher = CoroutineDispatcher() }
suspend fun <T> withContext(context: CoroutineDispatcher, block: suspend () -> T): T = block()
