package com.ttlock.ttlock_flutter

/**
 * 同一时刻只允许一路升级。网关网络 DFU 另记 [cacheGatewayMac]，供同 MAC 重试。
 */
object UpgradeSession {
    const val KIND_LOCK = "lock"
    const val KIND_GATEWAY = "gateway"
    const val KIND_WATER = "water"
    const val KIND_ELECTRIC = "electric"
    const val KIND_KEYPAD = "keypad"

    @Volatile
    var activeKind: String? = null

    @Volatile
    var cacheGatewayMac: String = ""

    private val sinks = HashMap<String, PigeonEventSink<TTUpgradeEvent>>()

    fun tryBegin(kind: String): Boolean {
        synchronized(this) {
            if (activeKind != null) return false
            activeKind = kind
            return true
        }
    }

    fun bind(kind: String, sink: PigeonEventSink<TTUpgradeEvent>) {
        synchronized(this) {
            if (activeKind == kind) {
                sinks[kind] = sink
            }
        }
    }

    fun peek(kind: String): PigeonEventSink<TTUpgradeEvent>? {
        synchronized(this) {
            if (activeKind != kind) return null
            return sinks[kind]
        }
    }

    /** 结束该路会话并取出 sink。该路未在进行时返回 null。 */
    fun take(kind: String): PigeonEventSink<TTUpgradeEvent>? {
        synchronized(this) {
            if (activeKind != kind) return null
            activeKind = null
            return sinks.remove(kind)
        }
    }

    fun end(kind: String) {
        synchronized(this) {
            if (activeKind == kind) {
                activeKind = null
                sinks.remove(kind)
            }
        }
    }

    fun isDeviceKind(kind: String?): Boolean {
        return kind == KIND_WATER || kind == KIND_ELECTRIC || kind == KIND_KEYPAD
    }
}
