package com.ttlock.ttlock_flutter

/**
 * 同一时刻只允许一路升级。网关网络 DFU 另记 [cacheGatewayMac]，供同 MAC 重试。
 *
 * 每次 [tryBegin] 成功都会递增该 kind 的代际。结束会话的回调必须带上开始时捕获的代际，
 * 迟到的 DFU / 锁系统信息回调不能结束下一次同 kind 会话。
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
    private val generations = HashMap<String, Long>()

    /**
     * 占有升级会话并递增 [kind] 代际。
     * 已有会话时返回 null；成功时返回本次代际，供回调捕获。
     */
    fun tryBegin(kind: String): Long? {
        synchronized(this) {
            if (activeKind != null) return null
            activeKind = kind
            val next = (generations[kind] ?: 0L) + 1L
            generations[kind] = next
            return next
        }
    }

    /** 该 kind 仍是当前会话，且代际与开始时捕获的一致。 */
    fun matches(kind: String, generation: Long): Boolean {
        synchronized(this) {
            return activeKind == kind && generations[kind] == generation
        }
    }

    fun bind(kind: String, generation: Long, sink: PigeonEventSink<TTUpgradeEvent>) {
        synchronized(this) {
            if (activeKind == kind && generations[kind] == generation) {
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

    /** 结束该路当前会话并取出 sink。该路未在进行时返回 null。stop / cancel 使用。 */
    fun take(kind: String): PigeonEventSink<TTUpgradeEvent>? {
        synchronized(this) {
            if (activeKind != kind) return null
            activeKind = null
            return sinks.remove(kind)
        }
    }

    /**
     * 仅当代际仍是当前会话时结束并取出 sink。
     * 迟到回调传入旧代际时返回 null，不会结束下一次同 kind 会话。
     */
    fun take(kind: String, generation: Long): PigeonEventSink<TTUpgradeEvent>? {
        synchronized(this) {
            if (activeKind != kind || generations[kind] != generation) return null
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
