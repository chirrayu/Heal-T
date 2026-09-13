package com.example.healthmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {

    val context  = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error    by remember { mutableStateOf("") }
    var loading  by remember { mutableStateOf(false) }
    val scope    = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07111F)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .background(Color(0xFF081B33), RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                text = "TRIAGE AI",
                color = Color(0xFF00E676),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Text(
                text = "COMMAND CENTER",
                color = Color(0xFF6B7F99),
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(32.dp))

            // Username field
            LoginField(
                label = "USERNAME",
                value = username,
                onValueChange = { username = it }
            )

            Spacer(Modifier.height(16.dp))

            // Password field
            LoginField(
                label = "PASSWORD",
                value = password,
                onValueChange = { password = it },
                isPassword = true
            )

            if (error.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(text = error, color = Color(0xFFFF445A), fontSize = 12.sp)
            }

            Spacer(Modifier.height(24.dp))

            // Login button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        if (loading) Color(0xFF00E676).copy(alpha = 0.5f)
                        else Color(0xFF00E676).copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, Color(0xFF00E676).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable(enabled = !loading) {
                        if (username.isBlank() || password.isBlank()) {
                            error = "Please enter username and password"
                            return@clickable
                        }
                        scope.launch {
                            loading = true
                            error = ""
                            val success = ApiService.login(username, password, context)
                            loading = false
                            if (success) {
                                onLoginSuccess()
                            } else {
                                error = "Invalid username or password"
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (loading) "LOGGING IN..." else "LOGIN",
                    color = Color(0xFF00E676),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFF6B7F99), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF07111F), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF1A3A5C), RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color(0xFF00C2FF)),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}