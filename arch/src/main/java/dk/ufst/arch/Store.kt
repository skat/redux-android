package dk.ufst.arch

import java.util.concurrent.CopyOnWriteArrayList

interface LocalStore<Value, Action> {
    fun subscribe(subscriber : (Value)->Unit)
    fun desubscribe(subscriber : (Value)->Unit)
    fun send(action: Action)
}

interface GlobalStore<Value, Action, Environment> {
    fun sendAction(action: Action)
    fun subscribe(subscriber: (Value) -> Unit)
    fun desubscribe(subscriber: (Value) -> Unit)
    val subscriberCount: Int
    val value: Value
}

internal class GlobalStoreImpl<Value, Action, Environment>(
    private val env : Environment,
    private val executor: Executor,
    initialValue : Value,
    private val copyValue: (Value) -> Value,
    private val reducer : ReducerFunc<Value, Action, Environment>
): GlobalStore<Value, Action, Environment> {
    private val subscriberList: CopyOnWriteArrayList<(Value) -> Unit> = CopyOnWriteArrayList()

    override var value = initialValue

    override fun sendAction(action: Action) {
        log("Dispatching action:")
        log("\t${getActionDescription(action as Any)}")
        // run on main thread
        executor.runOnUiThread {
            reduce(action, value)
        }
    }

    override fun subscribe(subscriber: (Value) -> Unit) {
        subscriberList.add(subscriber)
    }

    override fun desubscribe(subscriber: (Value) -> Unit) {
        subscriberList.remove(subscriber)
    }

    override val subscriberCount: Int
        get() = subscriberList.size

    private fun callSubscribers() {
        subscriberList.forEach { subscriber ->
            subscriber(value)
        }
    }

    private fun reduce(action: Action, currentValue: Value) {
        val newValue = copyValue(currentValue)
        val effects = reducer(newValue, action, env)
        value = newValue
        callSubscribers()
        effects.forEach { effect ->
            // run effect on thread pool
            executor.execute {
                val act = effect()
                act?.let {
                    sendAction(it) // send resulting action on main thread
                }
            }
        }
    }
}

fun <Value, Action, Environment> createGlobalStore(
    env : Environment,
    executor: Executor = ThreadExecutor(),
    initialValue : Value,
    copyValue: (Value) -> Value,
    reducer : ReducerFunc<Value, Action, Environment>): GlobalStore<Value, Action, Environment> {

    return GlobalStoreImpl(
        env = env,
        executor = executor,
        initialValue = initialValue,
        copyValue = copyValue,
        reducer = reducer
    )
}

@Suppress("unused")
inline fun <LocalValue, LocalAction, GlobalValue, reified GlobalAction, GlobalEnvironment> createLocalStore(
    globalStore: GlobalStore<GlobalValue, GlobalAction, GlobalEnvironment>,
    crossinline getLocalCopy : (GlobalValue)->LocalValue,
) : LocalStore<LocalValue, LocalAction> {
    return object : LocalStore<LocalValue, LocalAction> {

        private var globalSubscriber : ((GlobalValue) -> Unit)? = null

        override fun subscribe(subscriber: (LocalValue) -> Unit) {
            var prevLocalValue : LocalValue? = null

            globalSubscriber?.let { throw IllegalStateException("LocalStore must only be subscribed to once") }
            globalSubscriber = { globalValue: GlobalValue ->

                val newLocalValue = getLocalCopy(globalValue)

                // inform the subscriber only if the local state have changed
                if(prevLocalValue != newLocalValue) {
                    if(BuildConfig.DEBUG) {
                        prevLocalValue?.let {
                            logStateDiff(it, newLocalValue!!)
                        }
                    }
                    subscriber(newLocalValue)
                }
                prevLocalValue = newLocalValue
            }
            globalSubscriber?.let {
                globalStore.subscribe(it)
            }
        }

        override fun desubscribe(subscriber: (LocalValue) -> Unit) {
            globalSubscriber?.let {
                globalStore.desubscribe(it)
            }
            globalSubscriber = null
        }

        override fun send(action: LocalAction) {
            if(action is GlobalAction) {
                globalStore.sendAction(action as GlobalAction)
            }

        }
    }
}
