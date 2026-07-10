package io.databang.digidash.core.deepobd

import io.databang.digidash.domain.model.RawDtc
import io.databang.digidash.domain.model.RawField
import io.databang.digidash.domain.model.RawMeasuringBlock

/**
 * Best-effort KWP1281 host state machine over a Deep OBD-style adapter, built
 * from docs/DeepOBD-Observed-API.md. The adapter firmware performs the 5-baud
 * init and the low-level byte ack/echo; this class drives the block-level
 * protocol: pump the ECU's identification blocks (ACK each), then issue
 * group-read / DTC / clear request blocks and parse the responses.
 *
 * KWP1281 framing per block: `[len] [counter] [title] [data...] 0x03`, where
 * `len` = counter+title+data+end byte count, and the block counter increments
 * by one for every block in either direction. In the adapter's auto mode each
 * received byte arrives as a (data, status) pair, so RX is de-paired first.
 *
 * This is expected to need live tuning on the vehicle (ticket 14). Every byte
 * is visible through [LoggingSppTransport], so mismatches are debuggable from a
 * capture. Methods return typed failures and never throw into the client.
 */
/**
 * Runtime-tunable framing knobs so the KWP1281 wire behaviour can be adjusted
 * live at the vehicle (via the debug bridge) without rebuilding — the first
 * real contact will likely need one of these flipped.
 */
data class Kwp1281Config(
    /** Let the adapter firmware auto-detect the ECU baud (default) vs fixed 9600. */
    val autoBaud: Boolean = true,
    /** 5-baud init line driver: "both" (K+L, default), "l", or "k". */
    val initLine: String = "both",
    /** Block-data bytes arrive paired (data + 0x02 status) — de-pair by default.
     *  The init baud/key-byte reads are raw and bypass this. */
    val depair: String = "on",
    /** Kotlin builds the full [len][counter][title][data][03] block (else title+data only). */
    val buildFullBlock: Boolean = true,
    /** Send ACK (0x09) blocks ourselves (off if the firmware auto-acks). */
    val sendAcks: Boolean = true,
)

class Kwp1281Session(
    private val transport: SppTransport,
    private val baud: Int = 9600,
    private val ecuAddress: Int = 0x01,
    private val blockTimeoutMs: Long = 1500,
    private val config: Kwp1281Config = Kwp1281Config(),
    /** Invoked once if the keep-alive loop ends unexpectedly (socket/ECU drop),
     *  but NOT on an intentional [close]. Lets the client trigger reconnect. */
    private val onLost: (() -> Unit)? = null,
) {
    @Volatile private var closing = false
    private var counter = 0
    /** Baud actually used on the wire: the auto-detected rate once init runs. */
    private var activeBaud = baud
    private val idBlocks = mutableListOf<String>()

    /** ID ASCII blocks captured during connect (for the debug 'id' command). */
    fun identificationBlocks(): List<String> = idBlocks.toList()

    data class Block(val counter: Int, val title: Int, val data: ByteArray)

    companion object {
        /** Pseudo-title for adapter-local telegrams handled inside the loop. */
        const val TITLE_ADAPTER_VOLTAGE = -1
    }

    /** 5-baud init + key-byte handshake + pump identification blocks. */
    fun connect(): Result<List<String>> = runCatching {
        // KWP1281 requires ~2600 ms of K-line idle before the 5-baud wake.
        Thread.sleep(2600)
        val flags1 = when (config.initLine) {
            "l" -> AdapterProtocol.PULSE_FLAGS_L
            "k" -> AdapterProtocol.PULSE_FLAGS_K
            else -> AdapterProtocol.PULSE_FLAGS_BOTH
        }
        // In auto-baud mode the pulse carries the BaudAuto sentinel and the
        // adapter firmware measures the ECU sync + sends the ~KB2 ack itself.
        val pulseBaud = if (config.autoBaud) AdapterProtocol.BAUD_AUTO else baud
        val pulse = AdapterProtocol.pulseTelegram(ecuAddress, baud = pulseBaud, flags1 = flags1)
        android.util.Log.i(TAG, "kwp: TX pulse(addr=$ecuAddress auto=${config.autoBaud} line=${config.initLine}) ${hexOf(pulse)}")
        counter = 0
        transport.write(pulse)

        if (config.autoBaud) {
            // Adapter returns 2 bytes = baud/2 big-endian, then 2 key bytes.
            val baudBytes = readExact(2, 4000)
            android.util.Log.i(TAG, "kwp: RX baud ${hexOf(baudBytes)}")
            if (baudBytes.size < 2) error("no wake response (ignition on? K-line?)")
            val detected = (((baudBytes[0].toInt() and 0xFF) shl 8) or (baudBytes[1].toInt() and 0xFF)) shl 1
            android.util.Log.i(TAG, "kwp: detected baud = $detected")
            if (detected == 0) error("invalid baud detected")
            // All subsequent telegrams must be transmitted at the ECU's rate,
            // not the initial guess — otherwise the ECU never sees our ACKs.
            activeBaud = detected
            val kb = readExact(2, 1500)
            android.util.Log.i(TAG, "kwp: RX keybytes ${hexOf(kb)}")
            // Firmware sends the ~KB2 complement itself; host does nothing here.
        } else {
            // Legacy path: 1 sync byte + 2 key bytes, host sends ~KB2.
            val init = readRaw(4000)
            android.util.Log.i(TAG, "kwp: RX init ${init.size}: ${hexOf(init)}")
            if (init.size < 3) error("no sync/keybytes from ECU after init")
            val kb2Raw = init[2].toInt() and 0xFF
            Thread.sleep(40)
            transport.write(AdapterProtocol.kLineTelegram(baud, byteArrayOf((kb2Raw.inv() and 0xFF).toByte())))
        }

        // Pump ID blocks: read a block, ACK it, until the ECU sends an ACK block.
        idBlocks.clear()
        var guard = 0
        while (guard++ < 32) {
            val block = readBlock() ?: break
            when (block.title) {
                Kwp1281Protocol.TITLE_ASCII ->
                    idBlocks.add(String(block.data, Charsets.US_ASCII).trim())
                Kwp1281Protocol.TITLE_ACK -> { sendAck(); break }
            }
            sendAck()
        }
        invalidateMeasureState()
        // KWP1281 needs a continuous ACK keep-alive (<500 ms) or the ECU drops
        // the session; run the block exchange on a background loop that also
        // injects on-demand commands (group/DTC/basic-settings).
        startLoop()
        idBlocks.toList()
    }

    // --- Persistent keep-alive session loop ---

    private class PendingCommand(
        val title: Int,
        val data: ByteArray,
        /** Stop collecting and deliver once this holds for the received block. */
        val terminal: (Block) -> Boolean,
        /** Keep ACK (0x09) blocks in the result; else skip keep-alives. */
        val keepAcks: Boolean = false,
        /** Blocks to collect before giving up on a non-matching reply. */
        val giveUpBlocks: Int = 5,
        /** Sent IMMEDIATELY after this command's delivery, in the same turn
         *  slot (no intervening ACK) — required by the header/body group
         *  format where an ACK resets the ECU's header->body alternation. */
        val followUp: PendingCommand? = null,
    ) {
        val response = java.util.concurrent.SynchronousQueue<List<Block>>()
    }

    @Volatile private var running = false
    private var loopThread: Thread? = null
    private val commandQueue = java.util.concurrent.LinkedBlockingQueue<PendingCommand>()

    // --- Continuous measuring stream (kw1281test model: the 0x29 polling loop
    // IS the keep-alive; idle ACKs are suppressed while it runs). The ECU
    // LATCHES header->body per connection: first 0x29 after any ACK/command
    // returns the 0x02 header, subsequent back-to-back 0x29s return 0xF4
    // bodies until an ACK resets the latch (KLineKWP1281Lib ex03/ex04).
    class StreamSpec(
        val groups: List<Int>,
        val dwellBodies: Int = 4,
        /** 0x28 basic-settings stream instead of 0x29 group reads. */
        val basicSettings: Boolean = false,
    )

    @Volatile private var streamSpec: StreamSpec? = null
    /** Emitted for every decoded measuring block from the stream. */
    @Volatile var onMeasureBlock: ((RawMeasuringBlock) -> Unit)? = null
    /** Emitted when a group is refused/undecodable (repository retires it). */
    @Volatile var onGroupFailure: ((Int) -> Unit)? = null

    private enum class Latch { RESET, ARMED, STREAMING }
    private var latch = Latch.RESET
    private var latchGroup = -1
    private val headerCache = HashMap<Int, ByteArray>()
    private var streamIdx = 0
    private var bodiesLeft = 0
    private var awaitingStream = false

    /** Start/replace the continuous measuring stream (empty list stops it). */
    fun setMeasureStream(spec: StreamSpec?) {
        streamSpec = spec?.takeIf { it.groups.isNotEmpty() }
        streamIdx = 0
        bodiesLeft = spec?.dwellBodies ?: 0
    }

    private fun invalidateMeasureState() {
        headerCache.clear()
        latch = Latch.RESET
        latchGroup = -1
        awaitingStream = false
    }

    /** Host TX slot for the stream. Returns true if the turn was used. */
    private fun sendStreamRequest(spec: StreamSpec): Boolean {
        val g = spec.groups[streamIdx % spec.groups.size]
        if (g == 0) {
            // Group 0 = param-less raw display (0x12 -> 0xF4 10 bytes); it is a
            // non-0x29 command, so the latch resets for the next group.
            sendBlock(Kwp1281Protocol.TITLE_RAW_DATA_REQUEST, ByteArray(0))
            latch = Latch.RESET; latchGroup = -1
            awaitingStream = true
            return true
        }
        if (latch == Latch.RESET && latchGroup != g) {
            // Group switch / post-command: one deliberate ACK so the next 0x29
            // is guaranteed to yield a fresh header (latch is per-connection).
            sendAck()
            latchGroup = g
            return true
        }
        val title = if (spec.basicSettings) Kwp1281Protocol.TITLE_BASIC_SETTING
        else Kwp1281Protocol.TITLE_GROUP_REQUEST
        sendBlock(title, byteArrayOf(g.toByte()))
        awaitingStream = true
        return true
    }

    /** Dispatch a stream response strictly by TITLE (never positionally). */
    private fun handleStreamBlock(spec: StreamSpec, b: Block) {
        awaitingStream = false
        val g = spec.groups[streamIdx % spec.groups.size]
        fun advance() {
            streamIdx++
            bodiesLeft = spec.dwellBodies
        }
        fun emit(fields: List<RawField>) {
            onMeasureBlock?.invoke(RawMeasuringBlock(g, fields, System.currentTimeMillis()))
        }
        when {
            b.title == Kwp1281Protocol.TITLE_GROUP_HEADER -> {
                // Cache/refresh reactively (kw1281test discipline) and spend the
                // next TX turn re-requesting the same group for its body.
                headerCache[g] = b.data
                latch = Latch.ARMED; latchGroup = g
            }
            b.title == Kwp1281Protocol.TITLE_RAW_DATA_RESPONSE && g == 0 -> {
                emit(Kwp1281Protocol.decodeGroup000(b.data))
                if (--bodiesLeft <= 0) advance()
            }
            b.title == Kwp1281Protocol.TITLE_RAW_DATA_RESPONSE && b.data.size <= 4 -> {
                val head = headerCache[g]
                if (head == null) {
                    // Body with no cached header (e.g. latch survived a group
                    // switch): never decode against the wrong header.
                    latch = Latch.RESET; latchGroup = -1
                } else {
                    emit(Kwp1281Protocol.decodeHeaderBody(head, b.data))
                    latch = Latch.STREAMING; latchGroup = g
                    if (--bodiesLeft <= 0) advance()
                }
            }
            b.title == Kwp1281Protocol.TITLE_RAW_DATA_RESPONSE && b.data.size == 10 -> {
                // Basic-settings / group-0 style raw display.
                emit(Kwp1281Protocol.decodeGroup000(b.data))
                latch = Latch.RESET
                if (--bodiesLeft <= 0) advance()
            }
            b.title == Kwp1281Protocol.TITLE_GROUP_RESPONSE || b.data.size == 12 -> {
                emit(Kwp1281Protocol.decodeGroupResponse(b.data))
                latch = Latch.STREAMING; latchGroup = g
                if (--bodiesLeft <= 0) advance()
            }
            b.title == Kwp1281Protocol.TITLE_NO_DATA || b.title == Kwp1281Protocol.TITLE_ACK -> {
                // Refused / empty group: non-fatal, retire via callback.
                onGroupFailure?.invoke(g)
                latch = Latch.RESET; latchGroup = -1
                advance()
            }
            else -> {
                // Unexpected title: abort this read only; alternation is intact
                // because the block was consumed.
                android.util.Log.i(TAG, "kwp: stream unexpected title %02X for group %d".format(b.title, g))
                latch = Latch.RESET; latchGroup = -1
            }
        }
    }

    private fun startLoop() {
        running = true
        loopThread = Thread {
            var pending: PendingCommand? = null
            val collected = mutableListOf<Block>()
            var misses = 0
            var pendingBlocks = 0
            try {
            while (running) {
                // 1. Read the ECU's block (its turn).
                val ecuBlock = readBlock()
                if (ecuBlock == null) {
                    // Non-fatal: one silent turn (e.g. an unsupported command like
                    // a 0x29 this ECU doesn't implement) must NOT kill identity /
                    // DTC / keep-alive. Fail any pending command promptly, try an
                    // ACK to recover the turn, and only tear down after several
                    // consecutive misses (or a real transport exception below).
                    misses++
                    if (pending != null) {
                        pending.response.offer(emptyList()); pending = null; collected.clear()
                    }
                    if (misses >= 4) { running = false; break }
                    runCatching { sendAck() }
                    continue
                }
                misses = 0
                // 2a. A stream request in flight and no queued command pending:
                //     this block is the stream's response.
                val specNow = streamSpec
                if (pending == null && awaitingStream && specNow != null) {
                    handleStreamBlock(specNow, ecuBlock)
                    // Host transmit turn.
                    val next = commandQueue.poll()
                    if (next != null) {
                        // Commands preempt at body boundaries and reset the latch.
                        invalidateMeasureState()
                        if (next.title == TITLE_ADAPTER_VOLTAGE) {
                            handleAdapterVoltage(next)
                        } else {
                            sendBlock(next.title, next.data); pending = next
                            collected.clear(); pendingBlocks = 0
                        }
                    } else {
                        val sp = streamSpec
                        if (sp != null) sendStreamRequest(sp) else sendAck()
                    }
                    continue
                }
                // 2. Deliver to a waiting command (skipping keep-alive ACKs so an
                //    ACK-first reply isn't mistaken for the answer). CRITICAL: give
                //    up after a few blocks if the terminal never matches — else a
                //    non-answering command (e.g. an unsupported group) stays pending
                //    forever and the loop never sends the NEXT queued command.
                if (pending != null) {
                    pendingBlocks++
                    if (pending.keepAcks || ecuBlock.title != Kwp1281Protocol.TITLE_ACK) {
                        collected.add(ecuBlock)
                    }
                    if (pending.terminal(ecuBlock) || pendingBlocks >= pending.giveUpBlocks) {
                        pending.response.offer(collected.toList())
                        val chained = pending.followUp
                        collected.clear(); pending = null; pendingBlocks = 0
                        if (chained != null) {
                            // Use our transmit turn for the follow-up request
                            // instead of an ACK, then wait for the ECU's reply.
                            sendBlock(chained.title, chained.data)
                            pending = chained
                            continue
                        }
                    }
                }
                // 3. Host transmits exactly once: a queued command or a keep-alive ACK.
                if (pending == null) {
                    val next = commandQueue.poll()
                    if (next != null) invalidateMeasureState()
                    if (next == null) {
                        val sp = streamSpec
                        if (sp != null && !awaitingStream) {
                            sendStreamRequest(sp)
                            continue
                        }
                    }
                    if (next != null) {
                        if (next.title == TITLE_ADAPTER_VOLTAGE) {
                            handleAdapterVoltage(next)
                        } else {
                            sendBlock(next.title, next.data); pending = next
                            collected.clear(); pendingBlocks = 0
                        }
                    } else sendAck()
                } else {
                    sendAck() // still collecting a multi-block response
                }
            }
            } catch (e: Exception) {
                android.util.Log.i(TAG, "kwp: session loop ended: ${e.message}")
            } finally {
                running = false
                // Fail any pending/queued commands so no exchange() lingers to
                // its 2.5s timeout (which would hold the session mutex).
                runCatching { pending?.response?.offer(emptyList()) }
                while (true) {
                    val q = commandQueue.poll() ?: break
                    runCatching { q.response.offer(emptyList()) }
                }
                if (!closing) {
                    android.util.Log.i(TAG, "kwp: session lost (unexpected) — notifying")
                    runCatching { onLost?.invoke() }
                }
            }
        }.also { it.isDaemon = true; it.name = "kwp1281-session"; it.start() }
    }

    /** Enqueue a command and wait for its response block(s). Default: deliver the
     *  first non-ACK block; the loop delivers an empty list on a silent turn. */
    private fun exchange(
        title: Int,
        data: ByteArray,
        keepAcks: Boolean = false,
        terminal: (Block) -> Boolean = { it.title != Kwp1281Protocol.TITLE_ACK },
        giveUpBlocks: Int = 5,
    ): List<Block> {
        if (!running) error("session not connected")
        val cmd = PendingCommand(title, data, terminal, keepAcks, giveUpBlocks)
        commandQueue.offer(cmd)
        // Generous: at ~1200 baud a reply can queue behind a slow spontaneous
        // block read; the loop delivers an empty list promptly on a real miss.
        return cmd.response.poll(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            ?: error("no response (session timeout)")
    }

    /**
     * Battery voltage measured by the ADAPTER itself (FC status telegram,
     * x0.1 V) — same 12 V rail as the OBD socket. Executed inside the session
     * loop between blocks, so it never races the ECU exchange.
     */
    fun readAdapterVoltage(): Result<Double> = runCatching {
        val resp = exchange(TITLE_ADAPTER_VOLTAGE, ByteArray(0),
            keepAcks = true, terminal = { true })
        val b = resp.firstOrNull { it.title == TITLE_ADAPTER_VOLTAGE }
            ?: error("no adapter voltage reply")
        (b.data[0].toInt() and 0xFF) * 0.1
    }

    /**
     * KaPoder-style group read with com-error resync: request the group; if the
     * reply is not a 4-triplet block (12 data bytes — accepted regardless of
     * title, per the 1200-baud trial-and-error finding), send a 0x00 sync block
     * (resets the counter) and retry. Old 1200-baud ECUs interleave a static
     * 46-byte 0x02 table; the resync is what shakes the real group data loose.
     */
    fun readGroupResync(group: Int, attempts: Int = 4): Result<String> = runCatching {
        val log = StringBuilder()
        repeat(attempts) { attempt ->
            val resp = exchange(Kwp1281Protocol.TITLE_GROUP_REQUEST,
                byteArrayOf(group.toByte()),
                terminal = { it.title != Kwp1281Protocol.TITLE_ACK })
            val first = resp.firstOrNull { it.title != Kwp1281Protocol.TITLE_ACK }
            log.append("try$attempt: ")
            if (first == null) { log.append("no reply; ") }
            else {
                log.append("T=%02X len=%d [%s]; ".format(first.title, first.data.size,
                    first.data.joinToString(" ") { "%02X".format(it) }))
                if (first.data.size == 12 || first.title == Kwp1281Protocol.TITLE_GROUP_RESPONSE) {
                    val fields = Kwp1281Protocol.decodeGroupResponse(first.data)
                    log.append("DECODED: " + fields.joinToString(" ") { "${it.index}=${it.raw}" })
                    return@runCatching log.toString()
                }
            }
            // Not group data: com-error resync (0x00, counter reset) then retry.
            runCatching {
                // Full ident pump: the ECU replays its F6 blocks after a 0x00
                // sync — collect until its trailing ACK so the retried 0x29
                // never lands mid-replay.
                exchange(0x00, ByteArray(0), keepAcks = true,
                    terminal = { it.title == Kwp1281Protocol.TITLE_ACK }, giveUpBlocks = 8)
            }
            log.append("resync; ")
        }
        log.append("FAILED after $attempts attempts")
        log.toString()
    }

    /**
     * Debug: send an arbitrary block and collect EVERYTHING the ECU answers
     * (ACKs included) until the pending give-up — a protocol sniffer for
     * probing undocumented request formats live.
     */
    fun debugExchange(title: Int, data: ByteArray): Result<String> = runCatching {
        val blocks = exchange(title, data, keepAcks = true, terminal = { false }, giveUpBlocks = 8)
        blocks.joinToString(" | ") { b ->
            "T=%02X ctr=%02X [%s]".format(
                b.title, b.counter,
                b.data.joinToString(" ") { "%02X".format(it) })
        }.ifEmpty { "(no blocks)" }
    }

    fun readGroup(group: Int): Result<RawMeasuringBlock> = runCatching {
        // Early Digifant answers a PARAM-LESS raw read (title 0x12 -> 0xF4) for
        // group 0; numbered groups use the VAG-COM 0x29 -> 0xE7 service. Match the
        // SPECIFIC response title and skip the ECU's spontaneous 0x02/0xFC blocks,
        // else a group request gets crossed with an unrelated push.
        val reqTitle: Int
        val reqData: ByteArray
        val accept: Set<Int>
        if (group == 0) {
            reqTitle = Kwp1281Protocol.TITLE_RAW_DATA_REQUEST
            reqData = ByteArray(0)
            accept = setOf(Kwp1281Protocol.TITLE_RAW_DATA_RESPONSE)
        } else {
            reqTitle = Kwp1281Protocol.TITLE_GROUP_REQUEST
            reqData = byteArrayOf(group.toByte())
            // This early Digifant answers a 0x29 group read with the legacy 0x02
            // block (VCDS-style 0xE7 also accepted for other ECUs).
            accept = setOf(Kwp1281Protocol.TITLE_GROUP_RESPONSE, 0x02)
        }
        if (group == 0) {
            val resp = exchange(reqTitle, reqData,
                terminal = { it.title in accept || it.title == Kwp1281Protocol.TITLE_NO_DATA })
            val block = resp.firstOrNull { it.title in accept }
                ?: error("no measuring response for group 0")
            return@runCatching RawMeasuringBlock(
                group = 0,
                fields = Kwp1281Protocol.decodeGroup000(block.data),
                timestampMillis = System.currentTimeMillis(),
            )
        }
        // Numbered groups (SOURCE KLineKWP1281Lib): this ECU family answers
        // 0x29 with a GROUP HEADER (0x02) and delivers the GROUP BODY (0xF4,
        // one byte per zone) to an IMMEDIATE second 0x29 — an intervening ACK
        // resets the alternation, hence the followUp chain.
        if (!running) error("session not connected")
        val nonAck: (Block) -> Boolean = { it.title != Kwp1281Protocol.TITLE_ACK }
        val bodyCmd = PendingCommand(reqTitle, reqData, nonAck)
        val headCmd = PendingCommand(reqTitle, reqData, nonAck, followUp = bodyCmd)
        commandQueue.offer(headCmd)
        val headResp = headCmd.response.poll(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            ?: error("no header response for group $group")
        val bodyResp = bodyCmd.response.poll(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            ?: error("no body response for group $group")
        val head = headResp.firstOrNull(nonAck) ?: error("empty header reply for group $group")
        val fields = when {
            // Modern ECU: direct typed triplets — body reply is a duplicate, ignore.
            head.title == Kwp1281Protocol.TITLE_GROUP_RESPONSE || head.data.size == 12 ->
                Kwp1281Protocol.decodeGroupResponse(head.data)
            head.title == Kwp1281Protocol.TITLE_NO_DATA -> error("group $group not supported")
            head.title == Kwp1281Protocol.TITLE_GROUP_HEADER -> {
                val body = bodyResp.firstOrNull {
                    it.title == Kwp1281Protocol.TITLE_RAW_DATA_RESPONSE ||
                        it.title == Kwp1281Protocol.TITLE_GROUP_RESPONSE
                } ?: error("no body after header for group $group (got ${bodyResp.map { it.title }})")
                if (body.title == Kwp1281Protocol.TITLE_GROUP_RESPONSE)
                    Kwp1281Protocol.decodeGroupResponse(body.data)
                else Kwp1281Protocol.decodeHeaderBody(head.data, body.data)
            }
            else -> error("unexpected group $group reply title %02X".format(head.title))
        }
        RawMeasuringBlock(
            group = group,
            fields = fields,
            timestampMillis = System.currentTimeMillis(),
        )
    }

    fun readDtc(): Result<List<RawDtc>> = runCatching {
        // MEASURED on this ECU: all stored codes arrive in a single 0xFC block
        // and the trailing ACK may never come — deliver on the first response.
        // KNOWN LIMIT: an ECU with >4 stored faults may spread them over more
        // 0xFC blocks (KaPoder loops until ACK); revisit live if >4 ever occur.
        val resp = exchange(Kwp1281Protocol.TITLE_DTC_REQUEST, ByteArray(0))
        resp.filter { it.title == Kwp1281Protocol.TITLE_DTC_RESPONSE }
            .flatMap { Kwp1281Protocol.decodeDtcResponse(it.data) }
    }

    fun clearDtc(): Result<Unit> = runCatching {
        // The erase reply is an ACK (0x09) — deliver on any block and require it.
        val resp = exchange(Kwp1281Protocol.TITLE_DTC_CLEAR, ByteArray(0),
            keepAcks = true, terminal = { true })
        if (resp.none { it.title == Kwp1281Protocol.TITLE_ACK }) {
            error("clear not acknowledged by ECU")
        }
    }

    fun enterBasicSettings(group: Int): Result<RawMeasuringBlock> = runCatching {
        // Per the official 2E workshop manual: prog level <=1083 uses the ten-zone
        // group-00 display (param-less title 0x11 -> 0xF4); from level 1390 (ours
        // is 1576) function 04 takes a display group number -> standard 0x28.
        val accept = setOf(
            Kwp1281Protocol.TITLE_RAW_DATA_RESPONSE,
            Kwp1281Protocol.TITLE_GROUP_RESPONSE,
            0x02, // legacy raw reply used by this ECU for group reads
        )
        val resp = if (group == 0)
            exchange(Kwp1281Protocol.TITLE_BASIC_SETTING_START, ByteArray(0),
                terminal = { it.title in accept || it.title == Kwp1281Protocol.TITLE_NO_DATA })
        else
            exchange(Kwp1281Protocol.TITLE_BASIC_SETTING, byteArrayOf(group.toByte()),
                terminal = { it.title in accept || it.title == Kwp1281Protocol.TITLE_NO_DATA })
        val block = resp.firstOrNull { it.title in accept }
            ?: error("no basic-setting response")
        RawMeasuringBlock(
            group = group,
            fields = Kwp1281Protocol.decodeGroup(group, block.data),
            timestampMillis = System.currentTimeMillis(),
        )
    }

    fun exitBasicSettings(): Result<Unit> = runCatching {
        // The end-output reply is an ACK — accept any block.
        exchange(Kwp1281Protocol.TITLE_END_OUTPUT, ByteArray(0),
            keepAcks = true, terminal = { true })
        Unit
    }

    fun close() {
        closing = true
        running = false
        loopThread?.interrupt()
        loopThread = null
    }

    /** Adapter-local telegram (never reaches the ECU): the loop owns the
     *  transport, so run it between blocks and ACK to keep the ECU alive. */
    private fun handleAdapterVoltage(cmd: PendingCommand) {
        val raw = runCatching {
            transport.write(AdapterProtocol.READ_VOLTAGE)
            val r = transport.read(64, 800)
            if (r.size < 6) null else (r[r.size - 2].toInt() and 0xFF)
        }.getOrNull()
        cmd.response.offer(
            if (raw == null) emptyList()
            else listOf(Block(0, TITLE_ADAPTER_VOLTAGE, byteArrayOf(raw.toByte()))))
        sendAck()
    }

    private fun sendAck() {
        if (config.sendAcks) sendBlock(Kwp1281Protocol.TITLE_ACK, ByteArray(0))
    }

    private fun sendBlock(title: Int, data: ByteArray) {
        val payload = if (config.buildFullBlock) {
            counter = (counter + 1) and 0xFF
            ByteArray(data.size + 4).also {
                it[0] = (data.size + 3).toByte() // len: counter+title+data+end
                it[1] = counter.toByte()
                it[2] = title.toByte()
                data.copyInto(it, 3)
                it[it.size - 1] = 0x03
            }.also {
                // KaPoder com-error recovery: a 0x00 sync block resets the block
                // counter to 0 after transmission; the next RX re-adopts the
                // ECU's counter (readBlock always does).
                if (title == 0x00) counter = 0
            }
        } else {
            // Firmware frames the block; we send just the title + data.
            byteArrayOf(title.toByte(), *data)
        }
        android.util.Log.i(TAG, "kwp: TX block ${hexOf(payload)}")
        transport.write(AdapterProtocol.kLineTelegram(baud = activeBaud, payload = payload))
        // The ECU complement-acks every transmitted byte except the last 0x03,
        // and the firmware relays those single (unpaired) bytes back to us. Drain
        // them, or the odd count shifts the (data,status) pairing of the next
        // block and we read status bytes as data.
        if (config.depair == "on" && payload.size > 1) {
            val budget = maxOf(300L, (payload.size - 1) * (if (activeBaud <= 4800) 80L else 20L))
            val echo = readExact(payload.size - 1, budget)
            // Each echo byte must be the complement of the corresponding sent
            // byte — validate so a drift is visible in captures.
            var valid = 0
            for (i in echo.indices) {
                if (i < payload.size &&
                    (echo[i].toInt() and 0xFF) == (payload[i].toInt().inv() and 0xFF)) valid++
            }
            android.util.Log.i(TAG,
                "kwp: drained echo ${echo.size}/${payload.size - 1} complement-valid=$valid [${hexOf(echo)}]")
        }
    }

    private val TAG = "DIGIDASH_DBG"
    private fun hexOf(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * Read one block using the KWP1281 length prefix (OBDisplay-Uno approach):
     * read the length byte, then exactly that many more bytes. This avoids the
     * "stop at first 0x03" bug (short blocks like the ACK start with 0x03 as
     * their length) and tolerates the slow, gappy byte stream at low baud.
     * When [Kwp1281Config.depair] is on, every logical byte is a (data,status)
     * pair on the wire, so we read/skip two raw bytes per logical byte.
     */
    private fun readBlock(): Block? {
        val unit = if (config.depair == "on") 2 else 1
        // Sync to a plausible length byte: after we transmit a block the ECU
        // echoes the per-byte complement of our bytes (small block bytes → high
        // complements ≥ 0x80), which the firmware relays to us. Skip those and
        // any stray bytes until we see a valid KWP1281 length (3..64).
        var len = -1
        var scan = 0
        while (scan++ < 40) {
            val head = readExact(unit, blockTimeoutMs)
            if (head.size < unit) return null
            val v = head[0].toInt() and 0xFF
            if (v in 3..64) { len = v; break }
            android.util.Log.i(TAG, "kwp: RX skip ${hexOf(head)}")
        }
        if (len < 0) {
            android.util.Log.i(TAG, "kwp: RX no valid block length")
            return null
        }
        // Remaining logical bytes: counter, title, payload..., 0x03. Scale the
        // read window to the block size — a big block (e.g. the ECU's 49-byte
        // 0x02 measuring dump) needs far longer than an ACK at ~1200 baud.
        val body = readExact(len * unit, maxOf(blockTimeoutMs, len * 70L))
        if (body.size < len * unit) {
            android.util.Log.i(TAG, "kwp: RX block short ${body.size}/${len * unit}")
            return null
        }
        val logical = ByteArray(len) { body[it * unit] }
        android.util.Log.i(TAG, "kwp: RX block len=$len ${hexOf(byteArrayOf(len.toByte()) + logical)}")
        val blkCounter = logical[0].toInt() and 0xFF
        val title = logical[1].toInt() and 0xFF
        val data = if (len >= 3) logical.copyOfRange(2, len - 1) else ByteArray(0)
        counter = blkCounter
        return Block(blkCounter, title, data)
    }

    /**
     * De-pair adapter auto-mode RX: bytes arrive as (data, status) pairs. If the
     * stream length is odd (or already de-paired by firmware), fall back to raw.
     */
    private fun depair(raw: ByteArray): ByteArray = when (config.depair) {
        "on" -> if (raw.size >= 2) ByteArray(raw.size / 2) { raw[it * 2] } else raw
        "off" -> raw
        else -> if (raw.size >= 4 && raw.size % 2 == 0 && looksPaired(raw)) {
            ByteArray(raw.size / 2) { raw[it * 2] }
        } else raw
    }

    /** Heuristic: status bytes have the high bits mostly clear (delay*10ms). */
    private fun looksPaired(raw: ByteArray): Boolean {
        var oddHigh = 0
        var i = 1
        while (i < raw.size) {
            if ((raw[i].toInt() and 0x80) != 0) oddHigh++
            i += 2
        }
        return oddHigh == 0
    }

    /** Read exactly [n] bytes (accumulating) or return what arrived before timeout. */
    private fun readExact(n: Int, timeoutMs: Long): ByteArray {
        val out = ArrayList<Byte>(n)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size < n && System.currentTimeMillis() < deadline) {
            val c = transport.read(n - out.size, 200)
            if (c.isNotEmpty()) out.addAll(c.toList())
        }
        return out.toByteArray()
    }

    private fun readRaw(timeoutMs: Long): ByteArray {
        val chunks = ArrayList<Byte>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val c = transport.read(128, 150)
            if (c.isNotEmpty()) {
                chunks.addAll(c.toList())
                // A block ends with 0x03; stop early once we've seen it.
                if (c.any { it.toInt() and 0xFF == 0x03 }) break
            } else if (chunks.isNotEmpty()) {
                break
            }
        }
        return chunks.toByteArray()
    }
}
