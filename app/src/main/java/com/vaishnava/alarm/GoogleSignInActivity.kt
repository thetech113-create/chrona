@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.vaishnava.alarm

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.vaishnava.alarm.ui.theme.AlarmTheme

class GoogleSignInActivity : BaseActivity() {

    companion object {
        private const val TAG = "GoogleSignInActivity"
        private const val RC_SIGN_IN = 9001
    }

    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private lateinit var googleSignInClient: GoogleSignInClient
    private val cloudSyncInProgress = mutableStateOf(false)
    private val cloudSyncStatus = mutableStateOf<String?>(null)
    private val authInProgress = mutableStateOf(false)
    private val authErrorMessage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(DriveScopes.DRIVE_APPDATA),
                Scope(DriveScopes.DRIVE_FILE)
            )
            .requestIdToken(getString(R.string.default_web_client_id))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            val isDark = isSystemInDarkTheme()
            val view = LocalView.current

            AlarmTheme(darkTheme = isDark) {
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        window.statusBarColor = Color.TRANSPARENT
                        window.navigationBarColor = Color.TRANSPARENT
                        WindowCompat.getInsetsController(window, view)
                            .isAppearanceLightStatusBars = !isDark
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val currentUser = remember { mutableStateOf(firebaseAuth.currentUser) }
                    DisposableEffect(firebaseAuth) {
                        val listener = FirebaseAuth.AuthStateListener { auth ->
                            currentUser.value = auth.currentUser
                        }
                        firebaseAuth.addAuthStateListener(listener)
                        onDispose { firebaseAuth.removeAuthStateListener(listener) }
                    }

                    if (currentUser.value == null) {
                        GoogleSignInScreen(
                            isSigningIn = authInProgress.value,
                            errorMessage = authErrorMessage.value,
                            onSignInClick = { startGoogleSignIn() },
                            onBackClick = { finish() }
                        )
                    } else {
                        GoogleAccountScreen(
                            email = currentUser.value?.email ?: currentUser.value?.displayName.orEmpty(),
                            isWorking = cloudSyncInProgress.value,
                            statusMessage = cloudSyncStatus.value,
                            onSignOut = { signOut() },
                            onBackClick = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun startGoogleSignIn() {
        if (authInProgress.value) return
        authInProgress.value = true
        authErrorMessage.value = null
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account: GoogleSignInAccount = task.getResult(ApiException::class.java)!!
                Log.d(TAG, "Google & Drive sign-in success for: ${account.email}")
                handleGoogleSignInAccount(account)
            } catch (e: ApiException) {
                authInProgress.value = false
                Log.w(TAG, "Google sign-in failed: status=${e.statusCode}", e)
                authErrorMessage.value = "Google sign-in failed (${e.statusCode}). Please try again."
            }
        }
    }

    private fun handleGoogleSignInAccount(account: GoogleSignInAccount) {
        val idToken = account.idToken
        if (idToken.isNullOrBlank()) {
            authInProgress.value = false
            authErrorMessage.value = "Failed getting Google ID token."
            return
        }

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase sign-in completed in background for: ${firebaseAuth.currentUser?.email}")
                    val cloudStorage = CloudAlarmStorage(applicationContext)
                    cloudStorage.triggerAsyncSync()
                } else {
                    Log.w(TAG, "Firebase background sign-in failed", task.exception)
                }
            }

        // Trigger background sync & exit instantly back to MainActivity (0 delay!)
        val cloudStorage = CloudAlarmStorage(applicationContext)
        cloudStorage.triggerAsyncSync()

        val localAlarmCount = AlarmStorage(applicationContext).getAlarms().size
        val intent = Intent().apply {
            putExtra("alarms_restored", true)
            putExtra("alarms_count", localAlarmCount)
            putExtra("restored_count", localAlarmCount)
        }
        setResult(RESULT_OK, intent)
        Toast.makeText(this, "Signed in as ${account.email ?: ""}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun signOut() {
        cloudSyncInProgress.value = false
        cloudSyncStatus.value = null
        authInProgress.value = false
        authErrorMessage.value = null
        firebaseAuth.signOut()
        googleSignInClient.signOut().addOnCompleteListener {
            setResult(RESULT_OK)
            Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

@Composable
private fun GoogleAccountScreen(
    email: String,
    isWorking: Boolean,
    statusMessage: String?,
    onSignOut: () -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Google Account & Drive Sync",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Signed in as $email",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 12.dp),
            textAlign = TextAlign.Center
        )

        Text(
            text = "Alarms automatically back up to your Google Drive AppData folder and Firebase.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            textAlign = TextAlign.Center
        )
        if (isWorking) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        if (!statusMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = statusMessage,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onSignOut, enabled = !isWorking) { Text("Sign out") }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onBackClick, enabled = !isWorking) { Text("Back") }
    }
}

@Composable
fun GoogleSignInScreen(
    isSigningIn: Boolean,
    errorMessage: String?,
    onSignInClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Sign in with Google Drive",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Text(
                text = "Sign in with Google to allow Chrona to save and restore alarms directly to your Google Drive.",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 32.dp),
                textAlign = TextAlign.Center
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 20.dp),
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onSignInClick,
                enabled = !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = "Sign in with Google Drive",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onBackClick,
                enabled = !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Back",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
