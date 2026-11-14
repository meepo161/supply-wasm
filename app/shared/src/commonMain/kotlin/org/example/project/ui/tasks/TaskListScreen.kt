package org.example.project.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.TaskDto
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard

@Composable
fun TaskListScreen(
    tasks: List<TaskDto>,
    users: List<UserDto>,
    isLoading: Boolean,
    error: org.example.project.dto.ApiError?,
    onTaskClick: (TaskDto) -> Unit,
    onCreateClick: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Мои задачи",
                style = MaterialTheme.typography.headlineSmall
            )
            Row {
                Button(onClick = onCreateClick) {
                    Text("Создать")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onRefresh, enabled = !isLoading) {
                    Text("Обновить")
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        error?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Нет доступных задач. Создайте первую задачу.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            TasksTable(
                tasks = tasks,
                users = users,
                onTaskClick = onTaskClick,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
