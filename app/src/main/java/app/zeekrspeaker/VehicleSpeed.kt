package app.zeekrspeaker

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * Shared ECARX vehicle-speed reader.
 *
 * Uses the ECARX entry points and vehicle speed signal:
 * Car.create(Context) -> getSensorManager() -> getSensorLatestValue(0x00100100),
 * plus the sensor-value callback registered through registerListener().
 * Raw values are metres/second and are converted to km/h.
 */
object VehicleSpeed {
    private const val TAG = "VehicleSpeed"
    private const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
    private const val SPEED_SENSOR_ID = 0x00100100
    private const val POLL_INTERVAL_MS = 200L
    private const val MAX_READING_AGE_MS = 1_000L
    private const val RETRY_INTERVAL_MS = 2_000L
    private const val STATIONARY_TOLERANCE_KMH = 0.0f

    @Volatile private var speedKmh = Float.NaN
    @Volatile private var readingAtMs = 0L
    @Volatile private var callbackSeen = false
    @Volatile private var started = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var appContext: Context? = null
    private var car: Any? = null
    private var sensor: Any? = null
    private var latestValueMethod: Method? = null
    private var listenerProxy: Any? = null
    private var listenerRegistered = false
    private var lastConnectAttemptMs = 0L

    private val poll = object : Runnable {
        override fun run() {
            try {
                if (sensor == null || latestValueMethod == null) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastConnectAttemptMs >= RETRY_INTERVAL_MS) {
                        connect()
                    }
                }
                readLatest()
            } catch (t: Throwable) {
                Log.w(TAG, "Speed poll failed", t)
            } finally {
                handler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        val worker = HandlerThread("ecarx-speed-reader")
        worker.start()
        thread = worker
        handler = Handler(worker.looper)
        handler?.post {
            connect(app)
            poll.run()
        }
    }

    /** Returns the latest fresh speed, or null when unavailable/stale. */
    fun currentSpeedKmh(context: Context): Float? {
        start(context)
        val value = speedKmh
        val age = SystemClock.elapsedRealtime() - readingAtMs
        return if (value.isFinite() && readingAtMs > 0L && age in 0..MAX_READING_AGE_MS) value else null
    }

    /** Fail-closed playback gate. 0 km/h means stationary only. */
    fun isPlaybackAllowed(context: Context): Boolean {
        val limit = Prefs.maxVehicleSpeed(context)
        val speed = currentSpeedKmh(context) ?: return false
        return if (limit == 0) {
            speed <= STATIONARY_TOLERANCE_KMH
        } else {
            speed <= limit.toFloat()
        }
    }

    private fun connect(context: Context? = null) {
        lastConnectAttemptMs = SystemClock.elapsedRealtime()
        try {
            val app = context ?: appContext ?: return
            val carClass = Class.forName(CAR_CLASS)
            val create = carClass.methods.firstOrNull {
                it.name == "create" && it.parameterTypes.contentEquals(arrayOf(Context::class.java))
            } ?: throw NoSuchMethodException("Car.create(Context)")

            val newCar = create.invoke(null, app)
                ?: throw IllegalStateException("Car.create returned null")
            val newSensor = invokeNoArg(newCar, "getSensorManager")
                ?: throw IllegalStateException("getSensorManager returned null")
            val latest = findMethod(newSensor, "getSensorLatestValue", Integer.TYPE)
                ?: throw NoSuchMethodException("getSensorLatestValue(int)")

            car = newCar
            sensor = newSensor
            latestValueMethod = latest
            registerSpeedListener(newSensor)
            readLatest()
            Log.i(TAG, "ECARX speed reader connected; callback=${listenerRegistered}")
        } catch (t: Throwable) {
            sensor = null
            latestValueMethod = null
            listenerRegistered = false
            callbackSeen = false
            listenerProxy = null
            Log.w(TAG, "ECARX connection unavailable; playback remains blocked", t)
        }
    }

    private fun registerSpeedListener(sensorManager: Any) {
        if (listenerRegistered) return

        val interfaces = publicInterfaces(sensorManager)
        val withRate = interfaces.asSequence()
            .flatMap { it.methods.asSequence() }
            .firstOrNull { method ->
                val p = method.parameterTypes
                method.name == "registerListener" && p.size == 3 && p[0].isInterface &&
                    p[1] == Int::class.javaPrimitiveType && p[2] == Int::class.javaPrimitiveType
            }
        val plain = interfaces.asSequence()
            .flatMap { it.methods.asSequence() }
            .firstOrNull { method ->
                val p = method.parameterTypes
                method.name == "registerListener" && p.size == 2 && p[0].isInterface &&
                    p[1] == Int::class.javaPrimitiveType
            }

        val selected = withRate ?: plain ?: throw NoSuchMethodException("registerListener(listener, type[, rate])")
        val callbackType = selected.parameterTypes[0]
        val proxy = Proxy.newProxyInstance(
            callbackType.classLoader ?: VehicleSpeed::class.java.classLoader,
            arrayOf(callbackType),
            SpeedCallback()
        )
        listenerProxy = proxy

        var registered = false
        if (withRate != null) {
            val rate = findIntConstant(interfaces, "RATE_FAST") ?: 1
            registered = invokeRegistration(withRate, sensorManager, proxy, SPEED_SENSOR_ID, rate)
        }
        if (!registered && plain != null) {
            registered = invokeRegistration(plain, sensorManager, proxy, SPEED_SENSOR_ID)
        }
        listenerRegistered = registered
    }

    private fun invokeRegistration(method: Method, target: Any, vararg args: Any): Boolean {
        return try {
            val result = method.invoke(target, *args)
            result !is Boolean || result
        } catch (t: Throwable) {
            Log.w(TAG, "${method.name} registration failed", t)
            false
        }
    }

    private fun readLatest() {
        val manager = sensor ?: return
        val method = latestValueMethod ?: return
        try {
            val raw = method.invoke(manager, SPEED_SENSOR_ID)
            acceptRaw(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "getSensorLatestValue failed", t)
        }
    }

    private fun acceptRaw(raw: Any?) {
        if (callbackSeen) return
        val mps = (raw as? Number)?.toFloat() ?: return
        if (!mps.isFinite() || mps < 0f || mps > 100f) return
        speedKmh = mps * 3.6f
        readingAtMs = SystemClock.elapsedRealtime()
    }

    private class SpeedCallback : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "onSensorValueChanged" && args != null && args.size == 2 &&
                (args[0] as? Number)?.toInt() == SPEED_SENSOR_ID) {
                val raw = args[1]
                if (raw is Number) {
                    val mps = raw.toFloat()
                    if (mps.isFinite() && mps >= 0f && mps <= 100f) {
                        callbackSeen = true
                        speedKmh = mps * 3.6f
                        readingAtMs = SystemClock.elapsedRealtime()
                    }
                }
                return null
            }
            return when (method.name) {
                "toString" -> "VehicleSpeed.SensorCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args?.size == 1 && proxy === args[0]
                else -> primitiveDefault(method.returnType)
            }
        }
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE, java.lang.Short.TYPE, java.lang.Byte.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        val method = findMethod(target, name) ?: return null
        return method.invoke(target)
    }

    private fun findMethod(target: Any, name: String, vararg params: Class<*>): Method? {
        for (type in publicInterfaces(target)) {
            type.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(params) }?.let { return it }
        }
        return target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(params) }
    }

    private fun publicInterfaces(target: Any): List<Class<*>> {
        val found = LinkedHashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        var current: Class<*>? = target.javaClass
        while (current != null) {
            current.interfaces.forEach { queue.add(it) }
            current = current.superclass
        }
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (found.add(type)) type.interfaces.forEach { queue.add(it) }
        }
        return found.toList()
    }

    private fun findIntConstant(interfaces: List<Class<*>>, name: String): Int? {
        for (type in interfaces) {
            try {
                val field = type.getField(name)
                if (field.type == Int::class.javaPrimitiveType && Modifier.isStatic(field.modifiers)) {
                    return field.getInt(null)
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
