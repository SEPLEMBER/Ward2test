package org.syndes.terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

class MainActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var progressRow: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var progressJob: Job? = null

    private val terminal = Terminal()

    private val PREFS_NAME = "terminal_prefs"

    // For controlling glow specifically on the terminal output
    private var terminalGlowEnabled = true
    private var terminalGlowColor = Color.parseColor("#00FFF7")
    private var terminalGlowRadius = 6f

    // список "тяжёлых" команд, которые нужно выполнять в IO
    private val heavyCommands = setOf(
        "rm", "cp", "mv", "replace", "encrypt", "decrypt", "cmp", "diff",
        "rename", "backup", "snapshot", "trash", "cleartrash",
        "sha256", "grep", "batchrename", "md5", "delete all y"
    )

    // receiver to show watchdog results when service finishes
    private val watchdogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val cmd = intent?.getStringExtra("cmd") ?: return
                val result = intent.getStringExtra("result") ?: ""
                // use appendToTerminal which handles glow disabling for errors
                val infoColor = ContextCompat.getColor(this@MainActivity, R.color.color_info)
                appendToTerminal(colorize("\n[watchdog:$cmd] $result\n", infoColor), infoColor)
            } catch (t: Throwable) {
                // intentionally silent (logging removed)
            }
        }
    }

    // очередь команд для пакетного выполнения — теперь хранит либо одиночную команду, либо параллельную группу
    private val commandQueue = ArrayDeque<CommandItem>()
    private var processingJob: Job? = null
    private var processingQueue = false

    // background jobs (для команд с & и т.п.)
    private val backgroundJobs = mutableListOf<Job>()

    // CompletableDeferred used to wait for intent-based actions (uninstall)
    private var pendingIntentCompletion: CompletableDeferred<Unit>? = null

    // Launcher for intents that require user interaction and return control
    private val intentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // when system dialog/activity finishes - resume queue processing (if waiting)
        pendingIntentCompletion?.complete(Unit)
        pendingIntentCompletion = null
    }

    // Programmatically created stop-queue button
    private var stopQueueButton: Button? = null

    // Keys for extras/prefs
    private val EXTRA_SHORTCUT_CMD = "shortcut_cmd"
    private val EXTRA_SHORTCUT_LABEL = "shortcut_label"
    private val ACTION_RUN_SHORTCUT = "org.syndes.terminal.RUN_SHORTCUT"
    private val PREF_KEY_BOOT_SHELL = "bootshell_cmds"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // используем ADJUST_RESIZE — при появлении клавиатуры layout будет уменьшаться,
        // это предотвращает «уход» верхней части TextView за пределы экрана
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContentView(R.layout.activity_main)

        terminalOutput = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        progressRow = findViewById(R.id.progressRow)
        progressText = findViewById(R.id.progressText)
        progressBar = findViewById(R.id.progressBar)

        // Включаем прокрутку
        terminalOutput.movementMethod = ScrollingMovementMethod()

        // Вступительное сообщение (подсветка info)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        appendToTerminal(colorize("Welcome to Syndes Terminal!\nType 'help' to see commands.\n\n", infoColor), infoColor)

        // Переопределяем кнопку: текстовый вид, жёлтый цвет (вшитый)
        sendButton.text = "RUN"
        val embeddedYellow = Color.parseColor("#03A9F4")
        sendButton.setTextColor(embeddedYellow)
        sendButton.setBackgroundColor(Color.TRANSPARENT)

        // --- Apply subtle neon/glow effects (safe presets) ---
        // terminal output: cyan-ish glow (subtle) - save values for later toggling
        terminalGlowColor = Color.parseColor("#00FFF7")
        terminalGlowRadius = 6f
        terminalGlowEnabled = true
        applyNeon(terminalOutput, terminalGlowColor, radius = terminalGlowRadius)

        // progress text: warm yellow glow
        applyNeon(progressText, embeddedYellow, radius = 5f)

        // send button: keep its yellow text and add small glow
        applyNeon(sendButton, embeddedYellow, radius = 6f)

        // input field: small subtle greenish glow so caret/text pop
        val subtleGreen = Color.parseColor("#39FF14")
        applyNeon(inputField, subtleGreen, radius = 3f)

        // Добавляем кнопку "STOP QUEUE" программно (без изменения XML)
        addStopQueueButton()

        // Сделаем inputField явно фокусируемым в touch-mode (предотвращает потерю ввода)
        inputField.isFocusable = true
        inputField.isFocusableInTouchMode = true

        // Обработчики
        sendButton.setOnClickListener { sendCommand() }

        inputField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendCommand()
                true
            } else {
                false
            }
        }

        // Если пользователь тапает по выводу — переводим фокус на поле ввода и показываем клавиатуру
        terminalOutput.setOnClickListener {
            focusAndShowKeyboard()
        }

        // По умолчанию даём фокус полю и пытаемся показать клавиатуру (параметр устройства/IME может мешать)
        inputField.post {
            inputField.requestFocus()
        }

        // handle incoming intent (may be shortcut)
        handleIncomingIntent(intent)

        // boot shell: если есть автозагрузочная команда — выполнить её при старте (но НЕ показывать окно)
        checkBootShellOnStart()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // handle when app started via pinned shortcut (or other intents)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        try {
            if (intent == null) return
            // support both explicit action and extra-based invocation
            val cmdFromExtra = intent.getStringExtra(EXTRA_SHORTCUT_CMD)
            if (!cmdFromExtra.isNullOrBlank()) {
                // inject and run
                runOnUiThread {
                    appendToTerminal(colorize("\n[shortcut] running: $cmdFromExtra\n", ContextCompat.getColor(this, R.color.color_info)), ContextCompat.getColor(this, R.color.color_info))
                    inputField.setText(cmdFromExtra)
                    inputField.setSelection(inputField.text.length)
                    sendCommand()
                }
                return
            }

            if (intent.action == ACTION_RUN_SHORTCUT) {
                val cmd = intent.getStringExtra(EXTRA_SHORTCUT_CMD)
                if (!cmd.isNullOrBlank()) {
                    runOnUiThread {
                        appendToTerminal(colorize("\n[shortcut] running: $cmd\n", ContextCompat.getColor(this, R.color.color_info)), ContextCompat.getColor(this, R.color.color_info))
                        inputField.setText(cmd)
                        inputField.setSelection(inputField.text.length)
                        sendCommand()
                    }
                }
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    override fun onResume() {
        super.onResume()
        // register receiver for watchdog results (service broadcasts)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    watchdogReceiver,
                    IntentFilter("org.syndes.terminal.WATCHDOG_RESULT"),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(watchdogReceiver, IntentFilter("org.syndes.terminal.WATCHDOG_RESULT"))
            }
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(watchdogReceiver)
        } catch (_: Exception) { /* ignore */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideProgress()
        // cleanup background jobs
        backgroundJobs.forEach { it.cancel() }
        backgroundJobs.clear()
        processingJob?.cancel()
        pendingIntentCompletion?.completeExceptionally(CancellationException("activity destroyed"))
        pendingIntentCompletion = null
    }

    /**
     * sendCommand теперь поддерживает:
     * - многострочный ввод;
     * - разделители ';' и '&&' (&& — условное продолжение, выполняется следующая команда только если предыдущая успешна);
     * - ключевое слово "parallel" или "parallel:" для параллельного выполнения группы команд;
     * - суффикс '&' (space + &) для фонового запуска конкретной команды;
     *
     * Новые команды добавляются в очеред и выполняются последовательно (если не указано parallel/background).
     */
    private fun sendCommand() {
        val rawInput = inputField.text.toString()
        if (rawInput.isBlank()) {
            // если поле пустое - просто убедимся, что клавиатура видна и поле сфокусировано
            focusAndShowKeyboard()
            return
        }

        val inputColor = ContextCompat.getColor(this, R.color.color_command)

        // Парсим ввод в список CommandItem
        val items = parseInputToCommandItems(rawInput)

        // Добавляем элементы в очеред и печатаем в терминал
        for (item in items) {
            when (item) {
                is CommandItem.Single -> {
                    commandQueue.addLast(item)
                    appendToTerminal(colorize("\n> ${item.command}${if (item.background) " &" else ""}\n", inputColor), inputColor)
                }
                is CommandItem.Parallel -> {
                    commandQueue.addLast(item)
                    appendToTerminal(colorize("\n> parallel { ${item.commands.joinToString(" ; ")} }\n", inputColor), inputColor)
                }
            }
        }

        // Очищаем поле ввода
        inputField.text.clear()
        focusAndShowKeyboard()

        // Если сейчас не исполняется очередь — стартуем процесс
        if (!processingQueue) processCommandQueue()
    }

    // Add a STOP QUEUE button programmatically (no XML change)
    private fun addStopQueueButton() {
        try {
            if (stopQueueButton != null) return
            val btn = Button(this).apply {
                text = "STOP QUEUE"
                setTextColor(Color.parseColor("#FF5F1F"))
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { stopQueue() }
                visibility = View.GONE // hidden by default
            }
            // add neon glow to the created button (safe preset)
            applyNeon(btn, Color.parseColor("#FF5F1F"), radius = 6f)

            stopQueueButton = btn

            // Try to add before sendButton so STOP is left, RUN is right
            val parent = sendButton.parent
            if (parent is ViewGroup) {
                val idx = parent.indexOfChild(sendButton)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 16
                }
                parent.addView(btn, idx, lp) // insert before sendButton
            } else {
                // fallback: add to content root as overlay in top-left
                val root = findViewById<ViewGroup>(android.R.id.content)
                val flp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    topMargin = 8
                    leftMargin = 8
                }
                root.addView(btn, flp)
            }
        } catch (t: Throwable) {
            // ignore failure to add UI; functionality still available via programmatic stop if needed
        }
    }

    // Stop queue: cancel processing, clear queue, cancel background jobs and pending intent
    private fun stopQueue() {
        // clear the queue
        commandQueue.clear()

        // cancel processing job
        processingJob?.cancel(CancellationException("stopped by user"))
        processingJob = null
        processingQueue = false

        // cancel pending intent wait (if waiting for uninstall)
        try {
            pendingIntentCompletion?.completeExceptionally(CancellationException("stopped by user"))
        } catch (_: Throwable) { /* ignore */ }
        pendingIntentCompletion = null

        // cancel background jobs
        backgroundJobs.forEach { it.cancel(CancellationException("stopped by user")) }
        backgroundJobs.clear()

        // UI feedback
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        appendToTerminal(colorize("\n[STOP] queue stopped and cleared\n", infoColor), infoColor)
        scrollToBottom()
        hideProgress()
        stopQueueButton?.visibility = View.GONE
    }

    // Основной цикл обработки очереди (последовательно выполняем CommandItem)
    private fun processCommandQueue() {
        processingQueue = true
        // show stop button
        runOnUiThread { stopQueueButton?.visibility = View.VISIBLE }

        processingJob = lifecycleScope.launch {
            while (commandQueue.isNotEmpty() && isActive) {
                val item = commandQueue.removeFirst()
                try {
                    when (item) {
                        is CommandItem.Single -> {
                            // If background, start and don't wait
                            if (item.background) {
                                val bgJob = lifecycleScope.launch {
                                    try {
                                        runSingleCommand(item.command)
                                    } catch (_: Throwable) {
                                        // background failures are logged to terminal within runSingleCommand
                                    }
                                }
                                backgroundJobs.add(bgJob)
                                // don't wait; continue to next
                            } else {
                                // normal execution
                                runSingleCommand(item.command)
                            }
                        }
                        is CommandItem.Parallel -> {
                            // Validate that none of commands require intent-based user interaction (uninstall). If they do — disallow.
                            val hasIntentCommands = item.commands.any { it.trim().split("\\s+".toRegex()).firstOrNull()?.lowercase() in setOf("uninstall") }
                            if (hasIntentCommands) {
                                val err = ContextCompat.getColor(this@MainActivity, R.color.color_error)
                                withContext(Dispatchers.Main) {
                                    appendToTerminal(colorize("Error: cannot run uninstall or intent-based commands in parallel group. Skipping parallel group.\n", err), err)
                                }
                                continue
                            }
                            // Launch all commands concurrently and wait for all to complete
                            val deferredJobs = item.commands.map { cmd ->
                                lifecycleScope.launch {
                                    try {
                                        runSingleCommand(cmd)
                                    } catch (t: Throwable) {
                                        val err = ContextCompat.getColor(this@MainActivity, R.color.color_error)
                                        withContext(Dispatchers.Main) {
                                            appendToTerminal(colorize("Error (parallel): ${t.message}\n", err), err)
                                        }
                                    }
                                }
                            }
                            // Wait for all to complete
                            deferredJobs.joinAll()
                        }
                    }
                } catch (t: Throwable) {
                    val errorColor = ContextCompat.getColor(this@MainActivity, R.color.color_error)
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: failed to execute item : ${t.message}\n", errorColor), errorColor)
                    }
                }
            }
            processingQueue = false
            processingJob = null
            // hide stop button when done
            withContext(Dispatchers.Main) {
                stopQueueButton?.visibility = View.GONE
            }
        }
    }

    /**
     * Выполнение одной команды. Возвращает строковый результат (или сообщение об ошибке).
     * Функция сама пишет в терминал (progress, info), но также возвращает результат для условной логики.
     */
    private suspend fun runSingleCommand(command: String): String? {
        val inputToken = command.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: ""
        val defaultColor = ContextCompat.getColor(this@MainActivity, R.color.terminal_text)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)
        val errorColor = ContextCompat.getColor(this, R.color.color_error)
        val systemYellow = Color.parseColor("#FFD54F")

        // ==== NEW: act command (launch activity of any exported app) ====
        if (inputToken == "act") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: act <package> [<activity>]\nExamples:\n  act com.example.app\n  act com.example.app com.example.app.MainActivity\n  act com.example.app/.MainActivity\n", errorColor), errorColor)
                }
                return "Error: act usage"
            }
            val pkg = parts[1].trim()
            val activityPart = if (parts.size >= 3) parts.drop(2).joinToString(" ").trim() else null

            try {
                val launchIntent: Intent? = when {
                    activityPart.isNullOrBlank() -> {
                        // try getLaunchIntentForPackage
                        packageManager.getLaunchIntentForPackage(pkg)
                    }
                    else -> {
                        // allow formats: .Activity, com.pkg.Activity, pkg/.Activity
                        var actName = activityPart
                        if (actName.startsWith("/")) actName = actName.removePrefix("/")
                        // If activityPart starts with '.' -> make full name
                        if (actName.startsWith(".")) {
                            actName = "$pkg$actName"
                        } else if (actName.contains("/")) {
                            // handle com.pkg/.Some -> normalize
                            actName = actName.substringAfter('/')
                            if (actName.startsWith(".")) actName = "$pkg$actName"
                        }
                        Intent().apply {
                            setClassName(pkg, actName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                }

                if (launchIntent == null) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: cannot resolve activity for package '$pkg'\n", errorColor), errorColor)
                    }
                    return "Error: cannot resolve activity"
                }

                // Try to start activity; catch SecurityException if not exported or protected
                try {
                    withContext(Dispatchers.Main) {
                        startActivity(launchIntent)
                        appendToTerminal(colorize("Launched activity for package $pkg\n", infoColor), infoColor)
                    }
                    return "Info: activity launched"
                } catch (se: SecurityException) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: SecurityException when launching activity (not exported or requires permission)\n", errorColor), errorColor)
                    }
                    return "Error: security"
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: failed to launch activity: ${t.message}\n", errorColor), errorColor)
                    }
                    return "Error: launch failed"
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: act failed: ${t.message}\n", errorColor), errorColor)
                }
                return "Error: act"
            }
        }

        // ==== NEW: shortc command (create a shortcut that runs a terminal command) ====
        if (inputToken == "shortc") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 3) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: shortc <label> <command...>\nExample: shortc Reboot reboot && sleep 2s\n", errorColor), errorColor)
                }
                return "Error: shortc usage"
            }
            val label = parts[1]
            val cmd = parts.drop(2).joinToString(" ")

            try {
                val target = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_RUN_SHORTCUT
                    putExtra(EXTRA_SHORTCUT_CMD, cmd)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val sm = getSystemService(ShortcutManager::class.java)
                    if (sm != null) {
                        val id = "syd_shortcut_${System.currentTimeMillis()}"
                        val info = ShortcutInfo.Builder(this, id)
                            .setShortLabel(label)
                            .setIntent(target)
                            .build()
                        // prepare pending intent for confirmation callback (optional)
                        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        } else {
                            PendingIntent.FLAG_UPDATE_CURRENT
                        }
                        val confirmationIntent = PendingIntent.getBroadcast(this, 0, Intent(), piFlags)
                        val success = sm.requestPinShortcut(info, confirmationIntent.intentSender)
                        withContext(Dispatchers.Main) {
                            appendToTerminal(colorize("Shortcut requested: $label (command saved). If launcher supports pinning, confirm to add.\n", infoColor), infoColor)
                        }
                        return "Info: shortcut requested"
                    }
                }

                // Fallback for older devices / launchers: broadcast INSTALL_SHORTCUT (deprecated but works on some)
                val install = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                    putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)
                    putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
                    // no duplicate
                    putExtra("duplicate", false)
                    // icon: use app icon as fallback
                    try {
                        val appIcon = packageManager.getApplicationIcon(packageName)
                        // NOTE: putExtra(EXTRA_SHORTCUT_ICON) expects Parcelable Bitmap; skip complex conversion to keep simple
                    } catch (_: Throwable) { /* ignore */ }
                }
                sendBroadcast(install)
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Broadcasted shortcut install (legacy). If launcher supports it, shortcut added.\n", infoColor), infoColor)
                }
                return "Info: shortcut broadcasted"
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: cannot create shortcut: ${t.message}\n", errorColor), errorColor)
                }
                return "Error: shortc failed"
            }
        }

        // ==== NEW: bootshell command - show UI for autoload commands ====
        if (inputToken == "bootshell") {
            withContext(Dispatchers.Main) {
                showBootShellOverlay()
            }
            return "Info: bootshell opened"
        }

        // ==== NEW: sleep command ====
        if (inputToken == "sleep") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: sleep <duration>. Examples: sleep 5s | sleep 200ms | sleep 2m\n", errorColor), errorColor)
                }
                return "Error: sleep usage"
            }
            val durTok = parts[1]
            val millis = parseDurationToMillis(durTok)
            if (millis <= 0L) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: invalid duration '$durTok'\n", errorColor), errorColor)
                }
                return "Error: invalid duration"
            }
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Sleeping ${millis}ms...\n", systemYellow), systemYellow)
            }
            // suspend without blocking UI
            var remaining = millis
            val chunk = 500L
            while (remaining > 0 && coroutineContext.isActive) {
                val to = if (remaining > chunk) chunk else remaining
                delay(to)
                remaining -= to
            }
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Done sleep ${durTok}\n", infoColor), infoColor)
            }
            return "Info: slept ${durTok}"
        }

        // ==== NEW: runsyd command (reads script file from SAF root /scripts and injects into inputField) ====
        if (inputToken == "runsyd") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: runsyd <name>  (looks for name.syd in scripts folder)\n", errorColor), errorColor)
                }
                return "Error: runsyd usage"
            }
            val name = parts[1].trim()
            // read SAF root URI from prefs - prefer 'work_dir_uri', fallback to 'current_dir_uri'
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val safRoot = prefs.getString("work_dir_uri", null) ?: prefs.getString("current_dir_uri", null)
            if (safRoot.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: SAF root not configured. Set scripts folder in settings (work_dir_uri/current_dir_uri).\n", errorColor), errorColor)
                }
                return "Error: saf not configured"
            }
            try {
                val tree = DocumentFile.fromTreeUri(this, Uri.parse(safRoot))
                if (tree == null || !tree.isDirectory) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: cannot access SAF root (invalid URI)\n", errorColor), errorColor)
                    }
                    return "Error: saf root invalid"
                }
                val scriptsDir = tree.findFile("scripts")?.takeIf { it.isDirectory }
                if (scriptsDir == null) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: 'scripts' folder not found under SAF root\n", errorColor), errorColor)
                    }
                    return "Error: scripts folder missing"
                }

                // candidate filenames
                val candidates = if (name.contains('.')) {
                    listOf(name)
                } else {
                    listOf("$name.syd", "$name.sh", "$name.txt")
                }

                var found: DocumentFile? = null
                for (c in candidates) {
                    val f = scriptsDir.findFile(c)
                    if (f != null && f.isFile) {
                        found = f
                        break
                    }
                }

                if (found == null) {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: script not found: tried ${candidates.joinToString(", ")}\n", errorColor), errorColor)
                    }
                    return "Error: scripts not found"
                }

                // Read file text
                val uri = found.uri
                val sb = StringBuilder()
                contentResolver.openInputStream(uri)?.use { ins ->
                    BufferedReader(InputStreamReader(ins)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            sb.append(line).append('\n')
                        }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("Error: cannot open script file\n", errorColor), errorColor)
                    }
                    return "Error: cannot open"
                }

                val content = sb.toString().trimEnd()

                // inject into input field and run as if pasted
                withContext(Dispatchers.Main) {
                    inputField.setText(content)
                    inputField.setSelection(inputField.text.length)
                    appendToTerminal(colorize("Loaded script '${found.name}' — injecting commands...\n", infoColor), infoColor)
                    // call sendCommand to enqueue commands from file (this will add commands while we're processing)
                    sendCommand()
                }
                return "Info: runsyd loaded ${found.name}"
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: failed to read script: ${t.message}\n", errorColor), errorColor)
                }
                return "Error: runsyd failed"
            }
        }

        // ==== NEW: random {cmd1-cmd2-cmd3} command ====
        if (inputToken == "random") {
            // syntax: random {cmd1-cmd2-cmd3}
            val afterBrace = command.substringAfter('{', "").substringBefore('}', "")
            if (afterBrace.isBlank()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: random {cmd1-cmd2-cmd3}. Example: random {echo hi - sleep 1s - date}\n", errorColor), errorColor)
                }
                return "Error: random usage"
            }
            // split by '-' and trim options
            val options = afterBrace.split('-').map { it.trim() }.filter { it.isNotEmpty() }
            if (options.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: no options found inside {}\n", errorColor), errorColor)
                }
                return "Error: random no options"
            }
            // choose one randomly
            val idx = kotlin.random.Random.nextInt(options.size)
            val chosen = options[idx]
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Random chose: \"$chosen\"\n", infoColor), infoColor)
            }
            // execute chosen command and return its result
            return try {
                runSingleCommand(chosen)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: failed to run chosen command: ${t.message}\n", errorColor), errorColor)
                }
                "Error: random execution failed"
            }
        }

        // ==== NEW: button (echo: Question - opt1=cmd1 - opt2=cmd2 - ...) ====
        if (inputToken == "button") {
            // extract text inside parentheses
            val inside = command.substringAfter('(', "").substringBefore(')', "").trim()
            if (inside.isBlank()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: button (echo: Your question - Option1=cmd1 - Option2=cmd2 - ...)\n", errorColor), errorColor)
                }
                return "Error: button usage"
            }

            // split by '-' delimiter: first part is question (may start with 'echo:')
            val parts = inside.split('-').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: button: no parts found\n", errorColor), errorColor)
                }
                return "Error: button parse"
            }

            var question = parts[0]
            if (question.lowercase().startsWith("echo:")) {
                question = question.substringAfter(":", "").trim()
            }

            // parse options: label=command
            val opts = parts.drop(1).mapNotNull { p ->
                val eq = p.indexOf('=')
                if (eq <= 0) {
                    // If no '=', treat the whole token as both label and command
                    val lab = p
                    val cmd = p
                    lab to cmd
                } else {
                    val lab = p.substring(0, eq).trim()
                    val cmd = p.substring(eq + 1).trim()
                    if (lab.isEmpty() || cmd.isEmpty()) null else lab to cmd
                }
            }

            if (opts.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: button: no options provided (use Option=cmd)\n", errorColor), errorColor)
                }
                return "Error: button no options"
            }

            val selection = CompletableDeferred<String?>()
            var overlayView: View? = null

            // create modal overlay with question and buttons (on main thread)
            withContext(Dispatchers.Main) {
                try {
                    val root = findViewById<ViewGroup>(android.R.id.content)
                    val overlay = FrameLayout(this@MainActivity).apply {
                        // semi-transparent matte dark overlay: alpha + #0A0A0A
                        setBackgroundColor(Color.parseColor("#800A0A0A")) // alpha 0x80 + #0A0A0A
                        isClickable = true // consume touches
                    }

                    val container = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        val pad = (16 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        val lp = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                        layoutParams = lp
                        // matte dark panel background for button area
                        setBackgroundColor(Color.parseColor("#0A0A0A"))
                    }

                    val tv = TextView(this@MainActivity).apply {
                        text = question
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_info)) // keep message color
                        setTextIsSelectable(false)
                        val padv = (8 * resources.displayMetrics.density).toInt()
                        setPadding(padv, padv, padv, padv)
                    }
                    container.addView(tv)

                    // buttons column (vertical) — supports many options
                    val btnCol = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    for ((label, cmd) in opts) {
                        val b = Button(this@MainActivity).apply {
                            text = label
                            isAllCaps = false
                            setTextColor(Color.WHITE) // make button text white
                            setBackgroundColor(Color.TRANSPARENT)
                            setOnClickListener {
                                // complete with command
                                try {
                                    selection.complete(cmd)
                                } catch (_: Throwable) { /* ignore */ }
                                // remove overlay
                                try { root.removeView(overlay) } catch (_: Throwable) { }
                            }
                        }
                        // keep neon on buttons if desired (does not affect terminal glow)
                        try { applyNeon(b, Color.parseColor("#00FFF7"), radius = 4f) } catch (_: Throwable) {}
                        val blp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (6 * resources.displayMetrics.density).toInt()
                        }
                        btnCol.addView(b, blp)
                    }
                    container.addView(btnCol)

                    overlay.addView(container)
                    root.addView(overlay, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))

                    overlayView = overlay

                    // Also print question into terminal so user sees context in output
                    appendToTerminal(colorize("\n[button] $question\n", infoColor), infoColor)
                    appendToTerminal(colorize("[button] choose one of: ${opts.map { it.first }.joinToString(", ")}\n", infoColor), infoColor)
                } catch (t: Throwable) {
                    // UI creation failed
                    appendToTerminal(colorize("Error: cannot show button UI: ${t.message}\n", errorColor), errorColor)
                    selection.complete(null)
                }
            }

            // wait for selection or cancellation
            val chosenCmd: String? = try {
                selection.await()
            } catch (t: Throwable) {
                null
            } finally {
                // cleanup overlay if still present
                withContext(Dispatchers.Main) {
                    try {
                        overlayView?.let { rootView ->
                            val root = findViewById<ViewGroup>(android.R.id.content)
                            root.removeView(rootView)
                        }
                    } catch (_: Throwable) { /* ignore */ }
                }
            }

            if (chosenCmd.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Button selection cancelled or failed\n", errorColor), errorColor)
                }
                return "Error: button cancelled"
            }

            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Button selected — executing: $chosenCmd\n", infoColor), infoColor)
            }

            // execute chosen command (this will block queue until it finishes)
            return try {
                runSingleCommand(chosenCmd)
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: failed to execute chosen command: ${t.message}\n", errorColor), errorColor)
                }
                "Error: button execution failed"
            }
        }

        // Special-case: watchdog — same behavior as before: try service, else fallback timer that reinjects
        if (command.startsWith("watchdog", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("Scheduling watchdog: $command\n", infoColor), infoColor)
            }
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 3) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: watchdog <duration> <command...>\n", errorColor), errorColor)
                }
                return "Error: invalid watchdog syntax"
            }
            val durToken = parts[1]
            val targetCmd = parts.drop(2).joinToString(" ")
            val durSec = parseDurationToSeconds(durToken)
            if (durSec <= 0L) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Error: invalid duration '$durToken'\n", errorColor), errorColor)
                }
                return "Error: invalid duration"
            }

            try {
                val svcIntent = Intent(this@MainActivity, WatchdogService::class.java).apply {
                    putExtra(WatchdogService.EXTRA_CMD, targetCmd)
                    putExtra(WatchdogService.EXTRA_DELAY_SEC, durSec)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this@MainActivity, svcIntent)
                } else {
                    startService(svcIntent)
                }
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Watchdog service started: will run \"$targetCmd\" in $durToken\n", infoColor), infoColor)
                }
                return "Info: watchdog scheduled"
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Warning: cannot start watchdog service, falling back to in-app timer (may be cancelled when app backgrounded)\n", errorColor), errorColor)
                }
                // fallback: schedule reinjection
                lifecycleScope.launch {
                    try {
                        progressRow.visibility = TextView.VISIBLE
                        progressBar.isIndeterminate = true
                        var remaining = durSec
                        fun pretty(sec: Long): String {
                            val h = sec / 3600
                            val m = (sec % 3600) / 60
                            val s = sec % 60
                            return if (h > 0) String.format("%dh %02dm %02ds", h, m, s)
                            else if (m > 0) String.format("%02dm %02ds", m, s)
                            else String.format("%02ds", s)
                        }
                        progressText.text = "Watchdog: will run \"$targetCmd\" in ${pretty(remaining)}"
                        while (remaining > 0 && isActive) {
                            if (remaining >= 60L) {
                                var slept = 0L
                                while (slept < 60_000L && isActive) {
                                    delay(1000L)
                                    slept += 1000L
                                }
                                remaining -= 60L
                                if (remaining < 0L) remaining = 0L
                                progressText.text = "Watchdog: will run \"$targetCmd\" in ${pretty(remaining)}"
                            } else {
                                while (remaining > 0 && isActive) {
                                    delay(1000L)
                                    remaining -= 1L
                                    if (remaining < 0L) remaining = 0L
                                    progressText.text = "Watchdog: will run \"$targetCmd\" in ${pretty(remaining)}"
                                }
                            }
                        }
                        progressText.text = "Watchdog: executing \"$targetCmd\" now..."
                        delay(250)
                        // Re-inject target command into queue (в конец) — будет выполнен после текущих команд
                        commandQueue.addLast(CommandItem.Single(targetCmd, conditionalNext = false, background = false))
                        if (!processingQueue) processCommandQueue()
                    } catch (_: Throwable) {
                        withContext(Dispatchers.Main) {
                            appendToTerminal(colorize("Error: watchdog fallback failed\n", errorColor), errorColor)
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            progressRow.visibility = TextView.GONE
                            progressText.text = ""
                        }
                    }
                }
                return "Info: watchdog fallback scheduled"
            }
        }

        // clear
        if (command.equals("clear", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                terminalOutput.text = ""
            }
            val maybe = try {
                withContext(Dispatchers.Main) { terminal.execute(command, this@MainActivity) }
            } catch (_: Throwable) {
                null
            }
            if (maybe != null && !maybe.startsWith("Info: Screen cleared.", ignoreCase = true)) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize(maybe + "\n", infoColor), infoColor)
                }
            }
            return maybe ?: "Info: screen cleared"
        }

        // exit
        if (command.equals("exit", ignoreCase = true)) {
            withContext(Dispatchers.Main) {
                appendToTerminal(colorize("shutting down...\n", infoColor), infoColor)
            }
            delay(300)
            withContext(Dispatchers.Main) {
                finishAffinity()
            }
            return "Info: exit"
        }

        // uninstall <pkg> — intent-based, wait for result
        if (inputToken == "uninstall") {
            val parts = command.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (parts.size < 2) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Usage: uninstall <package.name>\n", errorColor), errorColor)
                }
                return "Error: uninstall usage"
            }
            val pkg = parts[1].trim()
            val installed = try {
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
            if (!installed) {
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Not installed: $pkg\n", errorColor), errorColor)
                }
                return "Error: not installed"
            }

            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
                .setData(Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)

            pendingIntentCompletion = CompletableDeferred()
            try {
                intentLauncher.launch(intent)
                // wait until activity result arrives or stop requested
                pendingIntentCompletion?.await()
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("Uninstall flow finished for $pkg\n", infoColor), infoColor)
                }
                val stillInstalled = try {
                    packageManager.getPackageInfo(pkg, 0)
                    true
                } catch (_: Exception) {
                    false
                }
                val msg = if (!stillInstalled) {
                    val s = "Info: package removed: $pkg"
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("$s\n", infoColor), infoColor)
                    }
                    s
                } else {
                    val s = "Info: package still installed: $pkg"
                    withContext(Dispatchers.Main) {
                        appendToTerminal(colorize("$s\n", defaultColor), defaultColor)
                    }
                    s
                }
                return msg
            } catch (t: Throwable) {
                pendingIntentCompletion = null
                val errMsg = "Error: cannot launch uninstall for $pkg: ${t.message}"
                withContext(Dispatchers.Main) {
                    appendToTerminal(colorize("$errMsg\n", errorColor), errorColor)
                }
                return errMsg
            }
        }

        // Other commands: heavy -> IO, else main
        val runInIo = heavyCommands.contains(inputToken)
        if (runInIo) {
            withContext(Dispatchers.Main) { showProgress("Working") }
            val result = try {
                withContext(Dispatchers.IO) {
                    try {
                        terminal.execute(command, this@MainActivity)
                    } catch (t: Throwable) {
                        "Error: ${t.message ?: "execution failed"}"
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { hideProgress() }
            }
            withContext(Dispatchers.Main) {
                handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
            }
            return result
        } else {
            val result = try {
                withContext(Dispatchers.Main) {
                    terminal.execute(command, this@MainActivity)
                }
            } catch (t: Throwable) {
                "Error: command execution failed"
            }
            withContext(Dispatchers.Main) {
                handleResultAndScroll(command, result, defaultColor, infoColor, errorColor, systemYellow)
            }
            return result
        }
    }

    // We parse raw input into CommandItem structures.
    // Supports: newline splitting, ";" and "&&" separators, "parallel" groups, and background suffix "&".
    private fun parseInputToCommandItems(raw: String): List<CommandItem> {
        val result = mutableListOf<CommandItem>()
        val lines = raw.lines()
        for (line0 in lines) {
            var line = line0.trim()
            if (line.isEmpty()) continue

            // If starts with "parallel" -> parse group
            if (line.startsWith("parallel ", ignoreCase = true) || line.startsWith("parallel:", ignoreCase = true)) {
                // remove keyword
                val rest = line.substringAfter(':', missingDelimiterValue = "").ifEmpty { line.substringAfter("parallel", "") }.trim().trimStart(':').trim()
                val groupText = if (rest.isEmpty()) "" else rest
                val parts = groupText.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    result.add(CommandItem.Parallel(parts))
                }
                continue
            }

            // Now split by separators ; and && preserving conditional semantics
            var i = 0
            val sb = StringBuilder()
            while (i < line.length) {
                if (i + 1 < line.length && line.substring(i, i + 2) == "&&") {
                    val token = sb.toString().trim()
                    if (token.isNotEmpty()) result.add(CommandItem.Single(token, conditionalNext = true, background = token.endsWith(" &")))
                    sb.setLength(0)
                    i += 2
                    continue
                } else if (line[i] == ';') {
                    val token = sb.toString().trim()
                    if (token.isNotEmpty()) result.add(CommandItem.Single(token, conditionalNext = false, background = token.endsWith(" &")))
                    sb.setLength(0)
                    i++
                    continue
                } else {
                    sb.append(line[i])
                    i++
                }
            }
            val last = sb.toString().trim()
            if (last.isNotEmpty()) result.add(CommandItem.Single(last, conditionalNext = false, background = last.endsWith(" &")))
        }

        // Clean up background markers (remove trailing & from command text) — only for Single items.
        // For Parallel items we leave them as-is.
        return result.map { item ->
            when (item) {
                is CommandItem.Single -> item.cleanupBackgroundSuffix()
                is CommandItem.Parallel -> item
            }
        }
    }

    private fun handleResultAndScroll(
        command: String,
        result: String?,
        defaultColor: Int,
        infoColor: Int,
        errorColor: Int,
        systemYellow: Int
    ) {
        if (result != null) {
            val firstToken = command.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: ""
            val resultColor = when {
                result.startsWith("Error", ignoreCase = true) -> errorColor
                result.startsWith("Info", ignoreCase = true) -> infoColor
                firstToken in setOf("mem", "device", "uname", "uptime", "date") -> systemYellow
                else -> defaultColor
            }
            appendToTerminal(colorize(result + "\n", resultColor), resultColor)
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoScroll = prefs.getBoolean("scroll_behavior", true)
        if (autoScroll) scrollToBottom()

        // подстраховка: гарантируем, что поле остаётся в фокусе и клавиатура видима
        inputField.post {
            inputField.requestFocus()
        }
    }

    private fun focusAndShowKeyboard() {
        inputField.post {
            inputField.requestFocus()
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    private fun showProgress(baseText: String) {
        progressRow.visibility = TextView.VISIBLE
        progressText.text = "$baseText..."
        // animate simple dots
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                val s = buildString {
                    append(baseText)
                    repeat(dots + 1) { append('.') }
                }
                progressText.text = s
                dots = (dots + 1) % 3
                delay(300)
            }
        }
    }

    private fun hideProgress() {
        progressJob?.cancel()
        progressJob = null
        progressRow.visibility = TextView.GONE
    }

    // Parse duration token like "15s", "5m", "2h" or plain number (seconds).
    private fun parseDurationToSeconds(tok: String): Long {
        if (tok.isEmpty()) return 0L
        val lower = tok.lowercase().trim()
        return try {
            when {
                lower.endsWith("s") && lower.length > 1 -> lower.dropLast(1).toLongOrNull() ?: 0L
                lower.endsWith("m") && lower.length > 1 -> (lower.dropLast(1).toLongOrNull() ?: 0L) * 60L
                lower.endsWith("h") && lower.length > 1 -> (lower.dropLast(1).toLongOrNull() ?: 0L) * 3600L
                else -> lower.toLongOrNull() ?: 0L
            }
        } catch (_: Throwable) { 0L }
    }

    // New helper: supports 'ms' suffix or falls back to parseDurationToSeconds * 1000
    private fun parseDurationToMillis(tok: String): Long {
        if (tok.isEmpty()) return 0L
        val lower = tok.lowercase().trim()
        return try {
            when {
                lower.endsWith("ms") && lower.length > 2 -> lower.dropLast(2).toLongOrNull() ?: 0L
                else -> parseDurationToSeconds(lower) * 1000L
            }
        } catch (_: Throwable) { 0L }
    }

    private fun colorize(text: String, color: Int): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(text)
        spannable.setSpan(
            ForegroundColorSpan(color),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /**
     * Append text to terminalOutput, but ensure that if the color equals the error color,
     * terminal glow is temporarily disabled for that append. This enforces "no glow for red text".
     */
    private fun appendToTerminal(sp: SpannableStringBuilder, color: Int) {
        val errorColor = ContextCompat.getColor(this@MainActivity, R.color.color_error)
        val needDisableGlow = (color == errorColor)
        // run on UI thread (safe to call from any thread)
        runOnUiThread {
            val prevGlow = terminalGlowEnabled
            if (needDisableGlow && prevGlow) {
                // temporarily disable glow
                setTerminalGlowEnabled(false)
            }
            try {
                terminalOutput.append(sp)
            } finally {
                if (needDisableGlow && prevGlow) {
                    // restore glow
                    setTerminalGlowEnabled(true)
                }
            }
            scrollToBottom()
        }
    }

    // Устанавливает глобально свечение для terminalOutput (в UI-потоке)
    private fun setTerminalGlowEnabled(enabled: Boolean) {
        try {
            if (enabled) {
                // restore glow
                terminalOutput.setShadowLayer(terminalGlowRadius, 0f, 0f, terminalGlowColor)
                terminalGlowEnabled = true
            } else {
                // disable glow
                terminalOutput.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                terminalGlowEnabled = false
            }
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun scrollToBottom() {
        terminalOutput.post {
            val layout = terminalOutput.layout ?: return@post
            val scrollAmount = layout.getLineTop(terminalOutput.lineCount) - terminalOutput.height
            if (scrollAmount > 0) terminalOutput.scrollTo(0, scrollAmount) else terminalOutput.scrollTo(0, 0)
        }
    }

    // Helper: apply a subtle neon glow using setShadowLayer (safe defaults chosen)
    private fun applyNeon(view: TextView, color: Int, radius: Float = 6f, dx: Float = 0f, dy: Float = 0f) {
        try {
            view.setShadowLayer(radius, dx, dy, color)
            // do not override text color here; caller already sets text color where needed
        } catch (_: Throwable) {
            // ignore devices that might fail; setShadowLayer is widely supported but be defensive
        }
    }

    // CommandItem sealed class represents either a single command or a parallel group
    private sealed class CommandItem {
        data class Single(val command: String, val conditionalNext: Boolean = false, val background: Boolean = false) : CommandItem() {
            fun cleanupBackgroundSuffix(): Single {
                var c = command
                if (background) {
                    // remove trailing & if present
                    c = c.removeSuffix("&").trimEnd()
                }
                return Single(c, conditionalNext, background)
            }
        }

        data class Parallel(val commands: List<String>) : CommandItem()
    }

    // ------------------- Boot shell overlay & helpers -------------------

    private fun checkBootShellOnStart() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(PREF_KEY_BOOT_SHELL, "") ?: ""
            if (saved.isNotBlank()) {
                // НЕ показываем overlay при старте, но выполняем сохранённые команды
                runOnUiThread {
                    appendToTerminal(colorize("\n[bootshell] auto-running saved commands\n", ContextCompat.getColor(this@MainActivity, R.color.color_info)), ContextCompat.getColor(this@MainActivity, R.color.color_info))
                    inputField.setText(saved)
                    inputField.setSelection(inputField.text.length)
                    // slight delay to allow UI to settle, затем запуск
                    lifecycleScope.launch {
                        delay(120)
                        sendCommand()
                    }
                }
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    /**
     * Show a simple dark overlay with an EditText for boot/autoload commands.
     * initialText - prefill the EditText
     * autoRun - if true, immediately inject initialText into inputField and call sendCommand()
     */
    private fun showBootShellOverlay(initialText: String = "", autoRun: Boolean = false) {
        try {
            val root = findViewById<ViewGroup>(android.R.id.content)
            // Prevent multiple overlays
            val existing = root.findViewWithTag<View>("bootshell_overlay")
            if (existing != null) {
                // bring to front
                existing.bringToFront()
                return
            }

            val overlay = FrameLayout(this).apply {
                tag = "bootshell_overlay"
                setBackgroundColor(Color.parseColor("#CC000000")) // semi-transparent dark
                isClickable = true
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (14 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply {
                    marginStart = (20 * resources.displayMetrics.density).toInt()
                    marginEnd = (20 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
                setBackgroundColor(Color.parseColor("#101010"))
            }

            val tv = TextView(this).apply {
                text = "BootShell — автозагрузка команд\n(вставьте команды; сохраните чтобы включить, очистите чтобы отключить)"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.color_info))
                val padv = (8 * resources.displayMetrics.density).toInt()
                setPadding(padv, padv, padv, padv)
            }
            container.addView(tv)

            val et = EditText(this).apply {
                isSingleLine = false
                minLines = 3
                maxLines = 10
                setText(initialText)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.terminal_text))
                setBackgroundColor(Color.TRANSPARENT)
                val p = (6 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }
            container.addView(et, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            })

            // Buttons row: Save | Run Now | Clear | Close
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val saveBtn = Button(this).apply {
                text = "Save"
                isAllCaps = false
                setOnClickListener {
                    val txt = et.text.toString()
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(PREF_KEY_BOOT_SHELL, txt).apply()
                    appendToTerminal(colorize("\n[bootshell] saved autoload commands\n", ContextCompat.getColor(this@MainActivity, R.color.color_info)), ContextCompat.getColor(this@MainActivity, R.color.color_info))
                }
            }
            val runNowBtn = Button(this).apply {
                text = "Run Now"
                isAllCaps = false
                setOnClickListener {
                    val txt = et.text.toString()
                    if (txt.isNotBlank()) {
                        inputField.setText(txt)
                        inputField.setSelection(inputField.text.length)
                        appendToTerminal(colorize("\n[bootshell] running autoload commands\n", ContextCompat.getColor(this@MainActivity, R.color.color_info)), ContextCompat.getColor(this@MainActivity, R.color.color_info))
                        sendCommand()
                    } else {
                        appendToTerminal(colorize("\n[bootshell] nothing to run\n", ContextCompat.getColor(this@MainActivity, R.color.color_info)), ContextCompat.getColor(this@MainActivity, R.color.color_info))
                    }
                }
            }
            val clearBtn = Button(this).apply {
                text = "Clear"
                isAllCaps = false
                setOnClickListener {
                    et.setText("")
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(PREF_KEY_BOOT_SHELL, "").apply()
                    appendToTerminal(colorize("\n[bootshell] autoload cleared\n", ContextCompat.getColor(this@MainActivity, R.color.color_info)), ContextCompat.getColor(this@MainActivity, R.color.color_info))
                }
            }
            val closeBtn = Button(this).apply {
                text = "Close"
                isAllCaps = false
                setOnClickListener {
                    try { root.removeView(overlay) } catch (_: Throwable) {}
                }
            }

            // style small spacing
            val paramsBtn = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * resources.displayMetrics.density).toInt()
            }
            btnRow.addView(saveBtn, paramsBtn)
            btnRow.addView(runNowBtn, paramsBtn)
            btnRow.addView(clearBtn, paramsBtn)
            btnRow.addView(closeBtn, paramsBtn)

            container.addView(btnRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * resources.displayMetrics.density).toInt()
            })

            overlay.addView(container)
            root.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // if autoRun requested and initialText present -> inject & run
            if (autoRun && initialText.isNotBlank()) {
                inputField.setText(initialText)
                inputField.setSelection(inputField.text.length)
                appendToTerminal(colorize("\n[bootshell] auto-running saved commands\n", ContextCompat.getColor(this@MainActivity, R.color.color_info)), ContextCompat.getColor(this@MainActivity, R.color.color_info))
                // slight delay to allow UI to settle
                lifecycleScope.launch {
                    delay(120)
                    sendCommand()
                }
            }
        } catch (t: Throwable) {
            appendToTerminal(colorize("Error: cannot show bootshell UI: ${t.message}\n", ContextCompat.getColor(this, R.color.color_error)), ContextCompat.getColor(this, R.color.color_error))
        }
    }

    // --------------------------------------------------------------------

}
