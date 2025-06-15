package com.task.pooper

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.task.pooper.PooperState.ActiveTask
import com.task.pooper.PooperState.OnBreak
import com.task.pooper.PooperState.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val factory = PooperViewModelFactory(application)
        val viewModel: PooperViewModel by viewModels { factory }

        setContent {
            MaterialTheme {
                PooperApp(viewModel)
            }
        }
    }
}

sealed class PooperIntent {
    object FinishOnboarding : PooperIntent()
    data class CreateTask(val name: String, val durationMinutes: Int) : PooperIntent()
    object StartBreak : PooperIntent()
    object EndBreak : PooperIntent()
    data class EndBreakToTask(val task: FocusTask) : PooperIntent()
    object LoadStats : PooperIntent()
    object Tick : PooperIntent()
    object TaskFinished : PooperIntent()
    object InterruptBreak : PooperIntent()
    object ShowStats : PooperIntent()
}

sealed class PooperState {
    object Onboarding : PooperState()
    object CreatingTask : PooperState()
    data class ActiveTask(val task: FocusTask) : PooperState()
    data class OnBreak(val remainingTime: Int, val returnToTask: FocusTask?) : PooperState()
    data class Stats(val stats: TaskStats) : PooperState()
}

@Entity
data class FocusTask(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    var remainingTime: Int,
    val duration: Int
)

@Entity
data class TaskRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val durationMinutes: Int
)

data class TaskStats(
    val taskCount: Int = 0,
    val totalMinutes: Int = 0,
    val completedTasks: List<String> = listOf()
)

data class OnboardingPage(
    val imageRes: Int,
    val title: String,
    val subtitle: String,
    val buttonText: AnnotatedString,
    val secondaryButtonText: AnnotatedString? = null
)

@Dao
interface TaskDao {
    @Insert
    suspend fun insertTask(record: TaskRecord)

    @Query("SELECT * FROM TaskRecord")
    suspend fun getAll(): List<TaskRecord>
}

@Database(entities = [TaskRecord::class], version = 1)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}

class StatsRepository(context: Context) {
    private val db = Room.databaseBuilder(context, TaskDatabase::class.java, "pooper-db").build()
    private val dao = db.taskDao()

    suspend fun recordTask(task: FocusTask) {
        dao.insertTask(TaskRecord(name = task.name, durationMinutes = task.duration / 60))
    }

    suspend fun getStats(): TaskStats {
        val records = dao.getAll()
        val filtered = records.filter { it.name.isNotBlank() }

        return TaskStats(
            taskCount = filtered.size,
            totalMinutes = filtered.sumOf { it.durationMinutes },
            completedTasks = filtered.map { it.name }
        )
        return TaskStats(
            taskCount = records.size,
            totalMinutes = records.sumOf { it.durationMinutes },
            completedTasks = records.map { it.name }
        )
    }
}

class PooperViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PooperViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PooperViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

    class PooperViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = mutableStateOf<PooperState>(PooperState.Onboarding)
    val state: State<PooperState> = _state
    private var currentTask: FocusTask? = null
    private var currentBreakTime: Int = 0
    private val statsRepo = StatsRepository(application.applicationContext)
    private var timer: CountDownTimer? = null
    private var isBreak = false
    private var breakFromUnfinishedTask = false

    fun dispatch(intent: PooperIntent) {
        when (intent) {
            is PooperIntent.CreateTask -> {
                isBreak = false
                val task = FocusTask(
                    name = intent.name,
                    remainingTime = intent.durationMinutes * 60,
                    duration = intent.durationMinutes * 60
                )
                currentTask = task
                _state.value = ActiveTask(task)
                startTimer(task.remainingTime) {
                    dispatch(PooperIntent.TaskFinished)
                }
            }

            PooperIntent.Tick -> {
                if (isBreak) {
                    currentBreakTime -= 1
                    val returnTask = if (breakFromUnfinishedTask) currentTask else null
                    _state.value = OnBreak(currentBreakTime, returnTask)
                } else {
                    currentTask?.let {
                        val updatedTask = it.copy(remainingTime = it.remainingTime - 1)
                        currentTask = updatedTask
                        _state.value = ActiveTask(updatedTask)
                    }
                }
            }

            PooperIntent.TaskFinished -> {
                currentTask?.let { task ->
                    viewModelScope.launch(Dispatchers.IO) {
                        statsRepo.recordTask(task)
                        withContext(Dispatchers.Main) {
                            currentTask = null
                            dispatch(PooperIntent.StartBreak)
                        }
                    }
                }
            }

            PooperIntent.StartBreak -> {
                isBreak = true
                currentBreakTime = 300
                val returnTask = if ((currentTask?.remainingTime ?: 0) > 0) currentTask else null
                breakFromUnfinishedTask = returnTask != null
                _state.value = OnBreak(currentBreakTime, returnTask)
                startTimer(currentBreakTime) {
                    dispatch(PooperIntent.EndBreak)
                }
            }

            PooperIntent.EndBreak -> {
                timer?.cancel()
                isBreak = false
                if (breakFromUnfinishedTask && currentTask != null) {
                    _state.value = ActiveTask(currentTask!!)
                } else {
                    _state.value = PooperState.CreatingTask
                }
                breakFromUnfinishedTask = false
            }

            PooperIntent.InterruptBreak -> {
                timer?.cancel()
                dispatch(PooperIntent.EndBreak)
            }

            PooperIntent.LoadStats, PooperIntent.ShowStats -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val stats = statsRepo.getStats()
                    _state.value = Stats(stats)
                }
            }

            is PooperIntent.EndBreakToTask -> {
                timer?.cancel()
                isBreak = false
                currentTask = intent.task
                _state.value = ActiveTask(intent.task)
            }

            PooperIntent.FinishOnboarding -> {
                _state.value = PooperState.CreatingTask
            }
        }
    }

    private fun startTimer(seconds: Int, onFinish: () -> Unit) {
        timer?.cancel()
        timer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                dispatch(PooperIntent.Tick)
            }

            override fun onFinish() {
                onFinish()
            }
        }.start()
    }
}

@Composable
fun PooperApp(viewModel: PooperViewModel) {
    val context = LocalContext.current

    if (!wasOnboardingShown(context)) {
        OnboardingScreen {
            markOnboardingShown(context)
            viewModel.dispatch(PooperIntent.FinishOnboarding)
        }
    } else {
        when (val currentState = viewModel.state.value) {
            is PooperState.Onboarding -> OnboardingScreen {
                viewModel.dispatch(PooperIntent.FinishOnboarding)
            }

            is PooperState.CreatingTask -> TaskCreationScreen(
                onTaskCreated = { name, mins ->
                    viewModel.dispatch(PooperIntent.CreateTask(name, mins))
                },
                onShowStats = {
                    viewModel.dispatch(PooperIntent.ShowStats)
                }
            )

            is PooperState.ActiveTask -> {
                LaunchedEffect(currentState.task.remainingTime) {}
                FocusTimerScreen(currentState.task.name, currentState.task.remainingTime) {
                    if (currentState.task.remainingTime > 0) {
                        viewModel.dispatch(PooperIntent.TaskFinished)
                    }
                }
            }

            is PooperState.OnBreak -> {
                LaunchedEffect(currentState.remainingTime) {}
                BreakScreen(
                    remainingTime = currentState.remainingTime,
                    returnToTask = currentState.returnToTask
                ) { task ->
                    if (task != null) {
                        viewModel.dispatch(PooperIntent.EndBreakToTask(task))
                    } else {
                        viewModel.dispatch(PooperIntent.EndBreak)
                    }
                }
            }

            is PooperState.Stats -> StatsScreen(currentState.stats) {
                viewModel.dispatch(PooperIntent.EndBreak)
            }
        }
    }
}

const val PREFS_NAME = "pooper_prefs"
const val KEY_ONBOARDING_SHOWN = "onboarding_shown"

val onboardingPages = listOf(
    OnboardingPage(
        R.drawable.ic_logo,
        "Welcome to POOPER 💩",
        "Peak Operational Optimizer for Personal Efficiency Regulation",
        buildAnnotatedString { append("Next") }
    ),
    OnboardingPage(
        R.drawable.ic_logo,
        "Focus Like a Champion 🧠",
        "Create a task and start the countdown. No distractions!",
        buildAnnotatedString { append("Next") }
    ),
    OnboardingPage(
        R.drawable.ic_logo,
        "Take a Break ☕",
        "After each task, enjoy your sacred rest. You've earned it.",
        buildAnnotatedString { append("Next") }
    ),
    OnboardingPage(
        R.drawable.ic_logo,
        "Track Your Stats 📊",
        "Your completed tasks are saved. Poop proudly.",
        getStrikethroughPoopText()
    )
)

fun markOnboardingShown(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit {
            putBoolean(KEY_ONBOARDING_SHOWN, true)
        }
}

fun wasOnboardingShown(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ONBOARDING_SHOWN, false)
}

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = onboardingPages
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 160.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                OnboardingPageContent(page = pages[page])
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DotsIndicator(
                totalDots = pages.size,
                selectedIndex = pagerState.currentPage
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (pagerState.currentPage < pages.lastIndex) {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        markOnboardingShown(context)
                        onFinish()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(text = pages[pagerState.currentPage].buttonText)
            }

            pages[pagerState.currentPage].secondaryButtonText?.let { text ->
                TextButton(onClick = {
                    markOnboardingShown(context)
                    onFinish()
                }) {
                    Text(text = text)
                }
            }
        }
    }
}

@Composable
fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = page.imageRes),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = page.title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = page.subtitle,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun DotsIndicator(totalDots: Int, selectedIndex: Int) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        repeat(totalDots) { index ->
            val color = if (index == selectedIndex)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)

            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

fun getStrikethroughPoopText(): AnnotatedString {
    return buildAnnotatedString {
        append("Let’s ")
        withStyle(style = SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            append("Poop")
        }
        append(" Work💩")
    }
}

@Composable
fun TaskCreationScreen(
    onTaskCreated: (String, Int) -> Unit,
    onShowStats: () -> Unit
) {
    var taskName by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("25") }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Create a Task", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = taskName,
                onValueChange = { taskName = it },
                label = { Text("Task Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = duration,
                onValueChange = { duration = it },
                label = { Text("Duration (min)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = onShowStats) {
                Text("📊 Stats")
            }
            Button(onClick = {
                val trimmedName = taskName.trim()
                if (trimmedName.isNotEmpty()) {
                    onTaskCreated(trimmedName, duration.toIntOrNull() ?: 25)
                } else {
                    Toast.makeText(context, "Task name cannot be empty 💩", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("▶️ Start")
            }
        }
    }
}

@Composable
fun FocusTimerScreen(taskName: String, remainingTime: Int, onFinish: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🧠 Stay Focused", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Focusing on: $taskName", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Time left: ${remainingTime / 60}:${(remainingTime % 60).toString().padStart(2, '0')}",
            fontSize = 32.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onFinish) { Text("Take a Break") }
    }
}

@Composable
fun BreakScreen(
    remainingTime: Int,
    returnToTask: FocusTask?,
    onBreakFinished: (FocusTask?) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("⏸️ Break Time!", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Remaining: ${remainingTime / 60}:${(remainingTime % 60).toString().padStart(2, '0')}",
            fontSize = 28.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onBreakFinished(returnToTask) }) {
            Text("End Break")
        }
    }
}

@Composable
fun StatsScreen(stats: TaskStats, onBack: () -> Unit) {
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text("📈 Pooping Stats", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Total Tasks Completed: ${stats.taskCount}")
        Text("Total Minutes Focused: ${stats.totalMinutes}")
        Text("Tasks Done:", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(stats.completedTasks.filter { it.isNotBlank() }) { task ->
                Text("- $task")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack) {
            Text("Back")
        }
    }
}

