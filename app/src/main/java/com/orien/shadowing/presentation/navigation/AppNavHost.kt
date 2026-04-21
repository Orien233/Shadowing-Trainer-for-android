package com.orien.shadowing.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.orien.shadowing.presentation.materiallist.MaterialListScreen
import com.orien.shadowing.presentation.sentencelist.SentenceListScreen
import com.orien.shadowing.presentation.training.TrainingScreen

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = MaterialListRoute) {

        composable<MaterialListRoute> {
            MaterialListScreen(
                onNavigateToSentences = { materialId ->
                    navController.navigate(SentenceListRoute(materialId))
                }
            )
        }

        composable<SentenceListRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<SentenceListRoute>()
            SentenceListScreen(
                onNavigateToTraining = { materialId, sentenceId ->
                    navController.navigate(TrainingRoute(materialId, sentenceId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<TrainingRoute> {
            TrainingScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
