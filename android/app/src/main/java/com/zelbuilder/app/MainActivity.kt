package com.zelbuilder.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.zelbuilder.app.service.BuildBridge
import com.zelbuilder.app.service.BuildStage
import com.zelbuilder.app.ui.GlassCard
import com.zelbuilder.app.ui.ZelBackground
import com.zelbuilder.app.ui.ZelButton
import com.zelbuilder.app.ui.ZelCyan
import com.zelbuilder.app.ui.ZelDeep
import com.zelbuilder.app.ui.ZelField
import com.zelbuilder.app.ui.ZelGold
import com.zelbuilder.app.ui.ZelGreen
import com.zelbuilder.app.ui.ZelNavy
import com.zelbuilder.app.ui.ZelPink
import com.zelbuilder.app.ui.ZelTheme
import com.zelbuilder.app.ui.ZelViolet
import com.zelbuilder.app.viewmodel.RepoMode
import com.zelbuilder.app.viewmodel.Screen
import com.zelbuilder.app.viewmodel.SourceType
import com.zelbuilder.app.viewmodel.ZelViewModel
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private val vm: ZelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZelTheme {
                ZelApp(vm)
            }
        }
    }
}

// ═══════════════════════════ ROOT ═══════════════════════════

@Composable
fun ZelApp(vm: ZelViewModel) {
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = vm.showSettings) { vm.closeSettings() }
    BackHandler(
        enabled = !vm.showSettings &&
            (vm.screen == Screen.ANALYSIS || vm.screen == Screen.DESIGN || vm.screen == Screen.CONSOLE)
    ) { vm.goBack() }

    ZelBackground {
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                    scaleIn(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow), initialScale = 0.92f))
                    .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
            },
            label = "screens"
        ) { target ->
            when (target) {
                Screen.SPLASH -> SplashScreen { vm.screen = Screen.MAIN }
                Screen.MAIN -> MainScreen(vm)
                Screen.ANALYSIS -> AnalysisScreen(vm)
                Screen.DESIGN -> DesignScreen(vm)
                Screen.CONSOLE -> ConsoleScreen(vm)
            }
        }

        AnimatedVisibility(
            visible = vm.showSettings,
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            SettingsScreen(vm)
        }

        val msg = vm.message
        if (msg != null) {
            AlertDialog(
                onDismissRequest = { vm.dismissMessage() },
                containerColor = ZelDeep,
                title = { Text("Zel Builder Royal", fontWeight = FontWeight.Bold) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { vm.dismissMessage() }) { Text("OK", color = ZelCyan) }
                }
            )
        }
    }
}

// ═══════════════════════════ 1. SPLASH ═══════════════════════════

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val word = "Zelvaryn"
    var shown by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(600)
        for (i in 1..word.length) {
            shown = i
            delay(260)
        }
        delay(900)
        onFinished()
    }

    val infinite = rememberInfiniteTransition(label = "splash")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val glow by infinite.animateFloat(
        initialValue = 0.3f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(160.dp).scale(pulse), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ZelViolet.copy(alpha = glow),
                            ZelCyan.copy(alpha = glow * 0.4f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.9f
                    ),
                    radius = size.minDimension * 0.9f,
                    center = center
                )
            }
            Text(
                "Z",
                style = TextStyle(
                    fontSize = 88.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    shadow = Shadow(ZelCyan, Offset.Zero, 40f)
                )
            )
        }
        Spacer(Modifier.height(36.dp))
        Row {
            word.forEachIndexed { index, ch ->
                val visible = index < shown
                val alpha by animateFloatAsState(
                    targetValue = if (visible) 1f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                    label = "letterAlpha"
                )
                val lift by animateDpAsState(
                    targetValue = if (visible) 0.dp else 28.dp,
                    animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
                    label = "letterLift"
                )
                Text(
                    ch.toString(),
                    modifier = Modifier.alpha(alpha).offset(y = lift),
                    style = TextStyle(
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        color = if (index == 0) ZelGold else Color.White,
                        shadow = Shadow(ZelViolet, Offset.Zero, 24f)
                    )
                )
            }
        }
    }
}

// ═══════════════════════════ 2. MAIN ═══════════════════════════

@Composable
fun MainScreen(vm: ZelViewModel) {
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        vm.onSourcePicked(SourceType.ZIP, it)
    }
    val htmlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        vm.onSourcePicked(SourceType.HTML, it)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        vm.onSourcePicked(SourceType.FOLDER, it)
    }
    val builds by vm.builds.collectAsState(initial = emptyList())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Zel Builder", fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("ROYAL", color = ZelGold, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, fontSize = 13.sp)
            }
            IconButton(onClick = { vm.showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }

        Text("Choose your project", fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))

        PickerCard("Select ZIP", "Android project archive", Icons.Default.Archive, 100) {
            zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        PickerCard("Select HTML", "Single page wrapped into an APK", Icons.Default.Code, 250) {
            htmlPicker.launch(arrayOf("text/html"))
        }
        PickerCard("Select Folder", "Pick a project directory", Icons.Default.Folder, 400) {
            folderPicker.launch(null)
        }

        AnimatedVisibility(
            visible = vm.sourceName.isNotEmpty(),
            enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) { it / 2 } + fadeIn()
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Selected", color = ZelCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(vm.sourceName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                ZelButton("Process File", Modifier.fillMaxWidth()) { vm.onProcessFile() }
            }
        }

        if (builds.isNotEmpty()) {
            Text("Recent builds", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
            GlassCard(Modifier.fillMaxWidth()) {
                builds.take(3).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.appName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(
                            b.status,
                            color = if (b.status == "SUCCESS") ZelGreen else ZelPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (vm.showRepoSheet) RepoSheet(vm)
}

@Composable
fun PickerCard(title: String, subtitle: String, icon: ImageVector, delayMs: Long, onClick: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) { it / 2 } + fadeIn()
    ) {
        GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(ZelViolet, ZelCyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        }
    }
}

// ═══════════════════════════ 3. REPOSITORY SHEET ═══════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoSheet(vm: ZelViewModel) {
    ModalBottomSheet(
        onDismissRequest = { vm.showRepoSheet = false },
        containerColor = Color(0xF0140B2E),
        contentColor = Color.White
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Repository", fontSize = 22.sp, fontWeight = FontWeight.Bold)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Create New", vm.repoMode == RepoMode.NEW, Modifier.weight(1f)) { vm.repoMode = RepoMode.NEW }
                ModeChip("Use Existing", vm.repoMode == RepoMode.EXISTING, Modifier.weight(1f)) { vm.repoMode = RepoMode.EXISTING }
                ModeChip("Delete", vm.repoMode == RepoMode.DELETE, Modifier.weight(1f)) { vm.repoMode = RepoMode.DELETE }
            }

            when (vm.repoMode) {
                RepoMode.NEW -> {
                    ZelField(
                        value = vm.newRepoName,
                        onValueChange = { vm.newRepoName = it },
                        label = "New repository name",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                RepoMode.EXISTING -> {
                    ZelDropdown(vm.repos, vm.selectedRepo, "Select a repository") { vm.selectedRepo = it }
                    Text(
                        "You will be asked if old files should be cleaned.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
                RepoMode.DELETE -> {
                    ZelDropdown(vm.repos, vm.selectedRepo, "Select a repository to delete") { vm.selectedRepo = it }
                    ZelButton(
                        "Delete Repository",
                        Modifier.fillMaxWidth(),
                        enabled = vm.selectedRepo.isNotBlank() && !vm.busy,
                        colors = listOf(ZelPink, Color(0xFFB71C1C))
                    ) { vm.showDeleteDialog = true }
                }
            }

            if (vm.busy || vm.reposLoading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = ZelCyan,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )
            }

            ZelButton(
                "Confirm Repository",
                Modifier.fillMaxWidth(),
                enabled = !vm.busy && vm.repoMode != RepoMode.DELETE
            ) { vm.confirmRepository() }
        }
    }

    if (vm.showCleanDialog) {
        AlertDialog(
            onDismissRequest = { vm.showCleanDialog = false },
            containerColor = ZelDeep,
            title = { Text("Clean repository?", fontWeight = FontWeight.Bold) },
            text = { Text("Do you want to clean this repo's old files?") },
            confirmButton = {
                TextButton(onClick = { vm.answerClean(true) }) { Text("Yes, clean", color = ZelCyan) }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerClean(false) }) { Text("No, keep", color = Color.White) }
            }
        )
    }

    if (vm.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { vm.showDeleteDialog = false },
            containerColor = ZelDeep,
            title = { Text("Delete repository?", fontWeight = FontWeight.Bold) },
            text = { Text("This permanently deletes " + vm.selectedRepo + " from GitHub.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSelectedRepo() }) { Text("Delete", color = ZelPink) }
            },
            dismissButton = {
                TextButton(onClick = { vm.showDeleteDialog = false }) { Text("Cancel", color = Color.White) }
            }
        )
    }
}

@Composable
fun ModeChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) ZelViolet else Color.White.copy(alpha = 0.08f),
        label = "chipBg"
    )
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun ZelDropdown(options: List<String>, selected: String, placeholder: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        GlassCard(Modifier.fillMaxWidth(), onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected.ifEmpty { placeholder },
                    Modifier.weight(1f),
                    color = if (selected.isEmpty()) Color.White.copy(alpha = 0.6f) else Color.White
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(ZelDeep)
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No repositories found", color = Color.White.copy(alpha = 0.6f)) },
                    onClick = { expanded = false }
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = Color.White) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ═══════════════════════════ 4. AI ANALYSIS & ICON ═══════════════════════════

@Composable
fun AnalysisScreen(vm: ZelViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val error = vm.analysisError
        if (vm.analyzing) {
            LottieScan(Modifier.size(200.dp))
            Spacer(Modifier.height(16.dp))
            Text("AI is analyzing files...", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(vm.analysisStatus, color = ZelCyan, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                Modifier.fillMaxWidth(0.7f),
                color = ZelCyan,
                trackColor = Color.White.copy(alpha = 0.15f)
            )
        } else if (error != null) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Analysis failed", color = ZelPink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(error)
                Spacer(Modifier.height(14.dp))
                ZelButton("Back", Modifier.fillMaxWidth()) { vm.goBack() }
            }
        } else {
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { shown = true }
            val iconScale by animateFloatAsState(
                targetValue = if (shown) 1f else 0.3f,
                animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
                label = "iconScale"
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Analysis complete", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    if (!vm.projectHasIcon) {
                        val preview = vm.iconPreview
                        val model: Any = if (preview != null) ByteBuffer.wrap(preview) else vm.iconUrl
                        SubcomposeAsyncImage(
                            model = model,
                            contentDescription = "Generated app icon",
                            modifier = Modifier
                                .size(160.dp)
                                .scale(iconScale)
                                .clip(RoundedCornerShape(36.dp)),
                            loading = {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = ZelCyan)
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No icon found - generated with Pollinations.ai", fontSize = 12.sp, color = ZelCyan, textAlign = TextAlign.Center)
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ZelGreen, modifier = Modifier.size(80.dp).scale(iconScale))
                        Text("App icon found in project", color = ZelGreen)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(vm.appName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(vm.packageName, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                    Spacer(Modifier.height(18.dp))
                    ZelButton("Continue", Modifier.fillMaxWidth()) { vm.continueToDesigner() }
                }
            }
        }
    }
}

// ═══════════════════════════ 5. TREE DESIGNER ═══════════════════════════

@Composable
fun DesignScreen(vm: ZelViewModel) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(vm.treeAnimating) {
        if (vm.treeAnimating) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(3200, easing = LinearOutSlowInEasing))
        }
    }
    val shown = if (vm.treeDone) 1f else maxOf(progress.value, 0.06f)

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Tree Designer", fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text(
            "Removes junk and flattens the project structure.",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(14.dp))

        GlassCard(Modifier.fillMaxWidth().height(260.dp)) {
            GrowingTree(shown, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))

        if (vm.treeAnimating) {
            Text("Cleaning node_modules, .git, build... flattening folders", color = ZelCyan, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        if (!vm.treeDone) {
            ZelButton("Design File Tree", Modifier.fillMaxWidth(), enabled = !vm.treeAnimating) { vm.designTree() }
        } else {
            Text(vm.treeStats, color = ZelGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.cleanedFiles) { file ->
                        Text(
                            file,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ZelButton("Push & Build", Modifier.fillMaxWidth(), colors = listOf(ZelPink, ZelViolet)) { vm.pushAndBuild() }
        }
    }
}

@Composable
fun GrowingTree(progress: Float, modifier: Modifier = Modifier) {
    val sway by rememberInfiniteTransition(label = "sway").animateFloat(
        initialValue = -0.03f, targetValue = 0.03f,
        animationSpec = infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "swayValue"
    )
    Canvas(modifier) {
        val base = Offset(size.width / 2f, size.height * 0.96f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(ZelViolet.copy(alpha = 0.45f), Color.Transparent),
                center = base,
                radius = size.width * 0.35f
            ),
            radius = size.width * 0.35f,
            center = base
        )
        drawBranch(base.x, base.y, -(Math.PI / 2).toFloat(), size.height * 0.23f, 0, 7, progress, sway)
    }
}

private fun DrawScope.drawBranch(
    x: Float,
    y: Float,
    angle: Float,
    length: Float,
    depth: Int,
    maxDepth: Int,
    progress: Float,
    sway: Float
) {
    val levelStart = depth.toFloat() / (maxDepth + 1)
    val levelEnd = (depth + 1f) / (maxDepth + 1)
    val t = ((progress - levelStart) / (levelEnd - levelStart)).coerceIn(0f, 1f)
    if (t <= 0f) return

    val fullX = x + cos(angle) * length
    val fullY = y + sin(angle) * length
    val endX = x + cos(angle) * length * t
    val endY = y + sin(angle) * length * t
    val color = lerp(Color(0xFF8D6E63), ZelGreen, depth.toFloat() / maxDepth)

    drawLine(
        color = color,
        start = Offset(x, y),
        end = Offset(endX, endY),
        strokeWidth = (maxDepth - depth + 1) * 2.4f,
        cap = StrokeCap.Round
    )

    if (depth == maxDepth) {
        drawCircle(ZelGreen.copy(alpha = 0.9f), radius = 6f * t, center = Offset(endX, endY))
        drawCircle(ZelCyan.copy(alpha = 0.25f * t), radius = 12f * t, center = Offset(endX, endY))
        return
    }
    val spread = 0.42f
    val next = length * 0.76f
    drawBranch(fullX, fullY, angle - spread + sway, next, depth + 1, maxDepth, progress, sway)
    drawBranch(fullX, fullY, angle + spread + sway, next, depth + 1, maxDepth, progress, sway)
}

// ═══════════════════════════ 7. CONSOLE ═══════════════════════════

@Composable
fun ConsoleScreen(vm: ZelViewModel) {
    val logs by BuildBridge.logs.collectAsState()
    val stage by BuildBridge.stage.collectAsState()
    val status by BuildBridge.status.collectAsState()
    val apk by BuildBridge.apkPath.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    val finished = stage == BuildStage.SUCCESS || stage == BuildStage.FAILED
    val fixing = stage == BuildStage.ABSTRACTING || stage == BuildStage.FINDING ||
        stage == BuildStage.FIXING || stage == BuildStage.REPUSHING

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            when {
                !finished -> LottieScan(Modifier.fillMaxSize())
                stage == BuildStage.SUCCESS ->
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ZelGreen, modifier = Modifier.size(96.dp))
                else ->
                    Icon(Icons.Default.Error, contentDescription = null, tint = ZelPink, modifier = Modifier.size(96.dp))
            }
        }
        Text(
            status.ifBlank { "Preparing..." },
            color = if (fixing) ZelGold else if (stage == BuildStage.FAILED) ZelPink else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))

        GlassCard(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(logs) { line ->
                    val lower = line.lowercase()
                    val color = when {
                        lower.contains("error") || lower.contains("failed") -> ZelPink
                        lower.contains("apk saved") || lower.contains("committed") -> ZelGreen
                        lower.contains("fixing") || lower.contains("ai is") || lower.contains("abstracting") -> ZelGold
                        else -> Color.White.copy(alpha = 0.85f)
                    }
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = color,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        if (finished) {
            Spacer(Modifier.height(12.dp))
            val path = apk
            if (stage == BuildStage.SUCCESS && path != null) {
                Text(path, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                ZelButton("Install APK", Modifier.fillMaxWidth(), colors = listOf(ZelGreen, ZelCyan)) { vm.installApk() }
                Spacer(Modifier.height(8.dp))
            }
            ZelButton("New Project", Modifier.fillMaxWidth()) { vm.newProject() }
        }
    }
}

private const val SCAN_JSON = """
{"v":"5.7.0","fr":30,"ip":0,"op":60,"w":200,"h":200,"nm":"scan","ddd":0,"assets":[],"layers":[
{"ddd":0,"ind":1,"ty":4,"nm":"ring","sr":1,
"ks":{"o":{"a":1,"k":[{"i":{"x":[0.4],"y":[1]},"o":{"x":[0.6],"y":[0]},"t":0,"s":[90]},{"t":60,"s":[0]}]},
"r":{"a":0,"k":0},"p":{"a":0,"k":[100,100,0]},"a":{"a":0,"k":[0,0,0]},
"s":{"a":1,"k":[{"i":{"x":[0.4,0.4,0.4],"y":[1,1,1]},"o":{"x":[0.6,0.6,0.6],"y":[0,0,0]},"t":0,"s":[20,20,100]},{"t":60,"s":[100,100,100]}]}},
"ao":0,"shapes":[{"ty":"gr","it":[
{"ty":"el","d":1,"s":{"a":0,"k":[170,170]},"p":{"a":0,"k":[0,0]},"nm":"Ellipse"},
{"ty":"st","c":{"a":0,"k":[0,0.9,1,1]},"o":{"a":0,"k":100},"w":{"a":0,"k":5},"lc":2,"lj":2,"nm":"Stroke"},
{"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100},"sk":{"a":0,"k":0},"sa":{"a":0,"k":0},"nm":"Transform"}
],"nm":"Group"}],"ip":0,"op":60,"st":0,"bm":0},
{"ddd":0,"ind":2,"ty":4,"nm":"sweep","sr":1,
"ks":{"o":{"a":0,"k":100},
"r":{"a":1,"k":[{"i":{"x":[1],"y":[1]},"o":{"x":[0],"y":[0]},"t":0,"s":[0]},{"t":60,"s":[360]}]},
"p":{"a":0,"k":[100,100,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},
"ao":0,"shapes":[{"ty":"gr","it":[
{"ty":"rc","d":1,"s":{"a":0,"k":[6,80]},"p":{"a":0,"k":[0,-40]},"r":{"a":0,"k":3},"nm":"Rect"},
{"ty":"fl","c":{"a":0,"k":[0.49,0.3,1,1]},"o":{"a":0,"k":100},"r":1,"nm":"Fill"},
{"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100},"sk":{"a":0,"k":0},"sa":{"a":0,"k":0},"nm":"Transform"}
],"nm":"Group"}],"ip":0,"op":60,"st":0,"bm":0},
{"ddd":0,"ind":3,"ty":4,"nm":"core","sr":1,
"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[100,100,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},
"ao":0,"shapes":[{"ty":"gr","it":[
{"ty":"el","d":1,"s":{"a":0,"k":[26,26]},"p":{"a":0,"k":[0,0]},"nm":"Ellipse"},
{"ty":"fl","c":{"a":0,"k":[1,0.84,0.33,1]},"o":{"a":0,"k":100},"r":1,"nm":"Fill"},
{"ty":"tr","p":{"a":0,"k":[0,0]},"a":{"a":0,"k":[0,0]},"s":{"a":0,"k":[100,100]},"r":{"a":0,"k":0},"o":{"a":0,"k":100},"sk":{"a":0,"k":0},"sa":{"a":0,"k":0},"nm":"Transform"}
],"nm":"Group"}],"ip":0,"op":60,"st":0,"bm":0}
]}
"""

@Composable
fun LottieScan(modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(SCAN_JSON))
    LottieAnimation(
        composition = composition,
        modifier = modifier,
        iterations = LottieConstants.IterateForever
    )
}

// ═══════════════════════════ 8. SETTINGS ═══════════════════════════

@Composable
fun SettingsScreen(vm: ZelViewModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ZelNavy.copy(alpha = 0.97f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.closeSettings() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Black)
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("API Keys", fontWeight = FontWeight.Bold, color = ZelCyan)
                Spacer(Modifier.height(10.dp))
                ZelField(vm.githubToken, { vm.githubToken = it }, "GitHub Token", Modifier.fillMaxWidth(), password = true)
                Spacer(Modifier.height(10.dp))
                ZelField(vm.aiApiKey, { vm.aiApiKey = it }, "AI API Key", Modifier.fillMaxWidth(), password = true)
                Spacer(Modifier.height(10.dp))
                ZelField(vm.aiBaseUrl, { vm.aiBaseUrl = it }, "AI Base URL", Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                ZelField(vm.aiModel, { vm.aiModel = it }, "AI Model", Modifier.fillMaxWidth())
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Run Builds in Background Service", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Keeps building with a notification and wake lock while the app is closed.",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = vm.backgroundService,
                        onCheckedChange = { vm.backgroundService = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = ZelViolet, checkedThumbColor = Color.White)
                    )
                }
            }

            ZelButton("Validate Connections", Modifier.fillMaxWidth(), enabled = !vm.validating) {
                vm.validateConnections()
            }

            if (vm.validationResult.isNotBlank()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(vm.validationResult, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
