package com.task.pooper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.task.pooper.ui.theme.PooperTheme
import android.app.*
import android.content.Context
import android.os.CountDownTimer
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = PooperViewModel(application)
        setContent {
            MaterialTheme {
                PooperApp(viewModel)
            }
        }
    }
}

sealed class PooperIntent {
    data class CreateTask(val name: String, val durationMinutes: Int) : PooperIntent()
    object StartBreak : PooperIntent()
    object EndBreak : PooperIntent()
    object LoadStats : PooperIntent()
    object Tick : PooperIntent()
    object TaskFinished : PooperIntent()
}

sealed class PooperState {
    object Onboarding : PooperState()
    object CreatingTask : PooperState()
    data class ActiveTask(val task: FocusTask) : PooperState()
    data class OnBreak(val remainingTime: Int) : PooperState()
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

data class TaskStats(val taskCount: Int = 0, val totalMinutes: Int = 0, val completedTasks: List<String> = listOf())

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
        return TaskStats(
            taskCount = records.size,
            totalMinutes = records.sumOf { it.durationMinutes },
            completedTasks = records.map { it.name }
        )
    }
}

class PooperViewModel(application: Application) : ViewModel() {
    private val _state = mutableStateOf<PooperState>(PooperState.Onboarding)
    val state: State<PooperState> = _state
    private var currentTask: FocusTask? = null
    private var currentBreakTime: Int = 0
    private val statsRepo = StatsRepository(application.applicationContext)
    private var timer: CountDownTimer? = null
    private var isBreak = false

    fun dispatch(intent: PooperIntent) {
        when (intent) {
            is PooperIntent.CreateTask -> {
                isBreak = false
                val task = FocusTask(name = intent.name, remainingTime = intent.durationMinutes * 60, duration = intent.durationMinutes * 60)
                currentTask = task
                _state.value = PooperState.ActiveTask(task)
                startTimer(task.remainingTime) {
                    dispatch(PooperIntent.TaskFinished)
                }
            }
            PooperIntent.Tick -> {
                if (isBreak) {
                    currentBreakTime -= 1
                    _state.value = PooperState.OnBreak(currentBreakTime)
                } else {
                    currentTask?.let {
                        val updatedTask = it.copy(remainingTime = it.remainingTime - 1)
                        currentTask = updatedTask
                        _state.value = PooperState.ActiveTask(updatedTask)
                    }
                }
            }
            PooperIntent.TaskFinished -> {
                currentTask?.let { task ->
                    viewModelScope.launch(Dispatchers.IO) {
                        statsRepo.recordTask(task)
                        withContext(Dispatchers.Main) {
                            dispatch(PooperIntent.StartBreak)
                        }
                    }
                }
            }
            PooperIntent.StartBreak -> {
                isBreak = true
                currentBreakTime = 300
                _state.value = PooperState.OnBreak(currentBreakTime)
                startTimer(currentBreakTime) {
                    dispatch(PooperIntent.EndBreak)
                }
            }
            PooperIntent.EndBreak -> {
                timer?.cancel()
                _state.value = PooperState.CreatingTask
            }
            PooperIntent.LoadStats -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val stats = statsRepo.getStats()
                    _state.value = PooperState.Stats(stats)
                }
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
    when (val currentState = viewModel.state.value) {
        is PooperState.Onboarding -> OnboardingScreen { viewModel.dispatch(PooperIntent.EndBreak) }
        is PooperState.CreatingTask -> TaskCreationScreen { name, mins ->
            viewModel.dispatch(PooperIntent.CreateTask(name, mins))
        }
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
            BreakScreen(currentState.remainingTime) {
                viewModel.dispatch(PooperIntent.EndBreak)
            }
        }
        is PooperState.Stats -> StatsScreen(currentState.stats)
    }
}

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Welcome to POOPER!", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "POOPER stands for: Peak Operational Optimizer for Personal Efficiency Regulation.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "This is not your average productivity app. Here’s how to use it:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.padding(8.dp)) {
                listOf(
                    "1. Create a task. Think of something you really need to focus on.",
                    "2. Set how many minutes you want to work on it.",
                    "3. Start the timer and DO NOT get distracted. Be the Pooper.",
                    "4. When the timer ends, you’ll get a break. Enjoy it.",
                    "5. Your completed tasks get tracked in the Pooping Stats."
                ).forEach { Text(it, fontSize = 14.sp) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "This app is scientifically unscientific and spiritually focused.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Light
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onContinue, modifier = Modifier.clip(RoundedCornerShape(10.dp))) {
                Text("Let’s Poop 💩")
            }
        }
    }
}

@Composable
fun TaskCreationScreen(onTaskCreated: (String, Int) -> Unit) {
    var taskName by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("25") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onTaskCreated(taskName, duration.toIntOrNull() ?: 25) }) {
            Text("Start Task")
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
        Text("Time left: ${remainingTime / 60}:${(remainingTime % 60).toString().padStart(2, '0')}", fontSize = 32.sp)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onFinish) { Text("Take a Break") }
    }
}

@Composable
fun BreakScreen(remainingTime: Int, onBreakFinished: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("⏸️ Break Time!", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Remaining: ${remainingTime / 60}:${(remainingTime % 60).toString().padStart(2, '0')}", fontSize = 28.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBreakFinished) { Text("End Break") }
    }
}

@Composable
fun StatsScreen(stats: TaskStats) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📈 Pooping Stats", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Total Tasks Completed: ${stats.taskCount}")
        Text("Total Minutes Focused: ${stats.totalMinutes}")
        Text("Tasks Done:", fontWeight = FontWeight.SemiBold)
        stats.completedTasks.forEach {
            Text("- $it")
        }
    }
}

